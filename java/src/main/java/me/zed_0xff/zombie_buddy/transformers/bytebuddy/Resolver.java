package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Internal.Meta;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasAnnotation;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.jar.asm.*;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class Resolver extends AbstractPatchAnnotationTransformerV2 {

    /** Delegates to {@code av} and emits {@link Logger#debug} with {@link ScopeTracker#path()} at each annotation callback. */
    private static final class AnnVisitor extends AnnotationVisitor {
        private final ScopeTracker<Object> tracker;

        AnnVisitor(int api, AnnotationVisitor av, ScopeTracker<Object> tracker) {
            super(api, av);
            this.tracker = tracker;
        }

        @Override
        public void visit(String name, Object value) {
            Logger.debug("visit   ", tracker.path(), name, value);
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Logger.debug("visitAnn", tracker.path(), name, descriptor);
            AnnotationVisitor av = super.visitAnnotation(name, descriptor);
            return av == null ? null : new AnnVisitor(api, av, tracker);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            Logger.debug("visitArr", tracker.path(), name);
            AnnotationVisitor av = super.visitArray(name);
            return av == null ? null : new AnnVisitor(api, av, tracker);
        }

        @Override
        public void visitEnd() {
            Logger.debug("visitEnd", tracker.path());
            //super.visit("value", "test");
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
            if (av != null && !isZBdesc(desc)) {
                // Logger.debug("m-ann", tracker.path(), m_ctx.getPatchTarget(), desc);
                // av = new AnnVisitor(ASM_API, av, tracker);
            }

            return av;
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int pidx, String desc, boolean visible) {
            AnnotationVisitor av = super.visitParameterAnnotation(pidx, desc, visible);

            while (av != null && !isZBdesc(desc)) {
                final Patch patch = m_ctx.getPatch();
                if (patch == null) { Logger.warn("Method has no patch:", tracker.path()); break; }

                var param = m_method.getParameters().get(pidx);
                String paramName = param.getName();
                MetaInfo mi = getMetaInfo(param);
                if (mi == null) { Logger.once.warn("Parameter has no meta annotation:", tracker.path(), pidx, paramName); break; }

                Class<?> targetAnnType = mi.meta().targetAnnotation();
                if (targetAnnType == void.class || !desc.equals(Type.getDescriptor(targetAnnType))) { break; }

                mi.annDesc()
                    .getAnnotationType()
                    .getDeclaredMethods()
                    .filter(m -> m.getDeclaredAnnotations().isAnnotationPresent(Patch.Internal.Flags.class))
                    .forEach(m -> {
                        Patch.Internal.Flags flags = m.getDeclaredAnnotations().ofType(Patch.Internal.Flags.class).load();
                        Logger.debug("  ", m, flags);
                    });

                // if (methods.isEmpty()) { Logger.warn("No annotation element found for parameter:", paramName, "in", mi.annDesc()); break; }
                //
                // MethodDescription.InDefinedShape method = methods.get(0);
                // AnnotationDescription flags = method.getDeclaredAnnotations().ofType(Patch.Internal.Flags.class);
                // if (flags == null) break;
                //
                // Logger.debug("flags", flags);

                //Logger.debug("p-ann", tracker.path(), pidx, p.getName(), ann.targetAnnotation());
                // var zbAnns = p.getDeclaredAnnotations().filter(a -> isZBdesc(a.getAnnotationType().getDescriptor()));
                // zbAnns.forEach(a -> Logger.debug("  ", a.getAnnotationType().getInternalName()));
                //av = new AnnVisitor(ASM_API, av, tracker);
                // av.visit("value", p.getName());
                break;
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
            Logger.debug("visit method", tracker.path(), name, desc);
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            return "<clinit>".equals(name) ? mv : new MtdVisitor(api, mv, tracker, name, desc);
        }
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        ScopeTracker<Object> tracker = new ScopeTracker<>();
        return new ClsVisitor(ASM_API, cw, tracker);
    }
}
