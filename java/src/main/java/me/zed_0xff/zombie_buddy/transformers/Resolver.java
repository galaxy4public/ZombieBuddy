package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.jar.asm.*;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class Resolver extends Transformer {

    static boolean needResolve(String desc) {
        return !desc.startsWith("Lme/zed_0xff/zombie_buddy/");
    }

    /** Delegates to {@code av} and emits {@link Logger#debug} with {@link ScopeTracker#path()} at each annotation callback. */
    private static final class AnnVisitor extends AnnotationVisitor {
        private final ScopeTracker<Object> tracker;

        AnnVisitor(int api, AnnotationVisitor av, ScopeTracker<Object> tracker) {
            super(api, av);
            this.tracker = tracker;
        }

        @Override
        public void visit(String name, Object value) {
            Logger.debug(tracker.path(), "visit", name, value);
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Logger.debug(tracker.path(), "visitAnnotation", name, descriptor);
            AnnotationVisitor av = super.visitAnnotation(name, descriptor);
            return av == null ? null : new AnnVisitor(api, av, tracker);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            Logger.debug(tracker.path(), "visitArray", name);
            AnnotationVisitor av = super.visitArray(name);
            return av == null ? null : new AnnVisitor(api, av, tracker);
        }

        @Override
        public void visitEnd() {
            Logger.debug(tracker.path(), "visitEnd");
            super.visitEnd();
        }
    }

    class MtdVisitor extends TrackingMethodVisitor {
        MethodDescription.InDefinedShape m_method;

        public MtdVisitor(int api, MethodVisitor mv, ScopeTracker<Object> tracker, String name, String desc) {
            super(api, mv, tracker, name, desc);

            var res = m_ctx.getCurrentTypeDesc().getDeclaredMethods().filter(m -> m.getInternalName().equals(name) && m.getDescriptor().equals(desc));
            if (res.size() == 1) {
                m_method = res.getOnly();
            } else if (res.size() > 1) {
                Logger.warn("Multiple methods found for", name, desc);
                m_method = null;
            } else {
                Logger.warn("No method found for", name, desc);
                Logger.warn("Declared methods:", m_ctx.getCurrentTypeDesc().getDeclaredMethods().stream().map(MethodDescription::getInternalName).toList());
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(desc, visible);
            if (av != null && needResolve(desc)) {
                Logger.debug("m-ann", tracker.path(), m_ctx.getPatchTarget(), desc);
                av = new AnnVisitor(ASM_API, av, tracker);
            }

            return av;
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int p, String desc, boolean visible) {
            AnnotationVisitor av = super.visitParameterAnnotation(p, desc, visible);
            if (av != null && needResolve(desc)) {
                Logger.debug("p-ann", tracker.path(), p, desc);
                av = new AnnVisitor(ASM_API, av, tracker);
            }

            return av;
        }
    }

    class ClsVisitor extends TrackingClassVisitor {
        public ClsVisitor(int api, ClassVisitor cv, ScopeTracker<Object> tracker) {
            super(api, cv, tracker);
            Logger.debug("visit class", tracker.path(), m_ctx.className());
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            return (name == "<clinit>") ? mv : new MtdVisitor(api, mv, tracker, name, desc);
        }
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        ScopeTracker<Object> tracker = new ScopeTracker<>();
        return new ClsVisitor(ASM_API, cw, tracker);
    }
}
