package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.transformers.AnnCache;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;

/*
 * Rewrites ByteBuddy-bound annotations paired with {@code @Patch.*}: {@link Patch.Internal.MapBool} maps booleans to {@code Class} literals ({@link org.objectweb.asm.Type}),
 * and {@link Patch.Internal.Flags} fills infer-from-name / single-element probes on parameters (see {@link #resolveMethodParams}).
 */
public class Resolver extends AbstractTransformer {
    @Override
    protected boolean transformNode(ClassNode cn) {
        // Logger.debug("Resolver.transformNode", cn.name, m_ctx.getPatch());
        if (m_ctx.getPatch() == null) return false;

        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            changed |= resolveMethodAnns(mn);
            changed |= resolveMethodParams(mn);
        }

        return changed;
    }

    private boolean resolveMethodAnns(MethodNode mn) {
        if (Utils.isBlank(mn.visibleAnnotations)) return false;

        Map<String, AnnotationNode> zbByTarget = new HashMap<>();
        boolean isAdvice = m_ctx.getPatch().isAdvice();
        for (AnnotationNode ann : mn.visibleAnnotations) {
            if (!ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) continue;

            var meta = AnnCache.getMeta(ann.desc, isAdvice);
            if (meta == null || meta.targetAnnotation() == void.class) continue;

            zbByTarget.put(Type.getDescriptor(meta.targetAnnotation()), ann);
        }

        boolean changed = false;
        for (AnnotationNode ann : mn.visibleAnnotations) {
            if (ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) continue;

            AnnotationNode zbAnn = zbByTarget.get(ann.desc);
            if (zbAnn == null) continue;

            changed |= applyMapBoolFromZbAnn(ann, zbAnn);
        }

        return changed;
    }

    private boolean resolveMethodParams(MethodNode mn) {
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        // Logger.debug("resolveMethodParams", mn, argTypes);
        if (argTypes.length == 0) return false;

        boolean changed = false;
        for (int pidx = 0; pidx < argTypes.length; pidx++) {
            String paramName = getArgName(mn, pidx);
            if (Utils.isBlank(paramName)) continue;

            changed |= resolveParamAnns(mn.visibleParameterAnnotations, pidx, paramName);
        }

        return changed;
    }

    private boolean resolveParamAnns(List<AnnotationNode>[] lists, int pidx, String paramName) {
        // Logger.debug("resolveParamAnns", lists, pidx, paramName);
        if (lists == null || pidx >= lists.length) return false;

        List<AnnotationNode> plist = lists[pidx];
        if (Utils.isBlank(plist)) return false;

        Map<String, AnnotationNode> annMap = new HashMap<>();
        boolean isAdvice = m_ctx.getPatch().isAdvice();
        for (AnnotationNode ann : plist) {
            if (ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) { // scan only ZB annotations
                var meta = AnnCache.getMeta(ann.desc, isAdvice);
                if (meta == null || meta.targetAnnotation() == void.class) continue;

                annMap.put(Type.getDescriptor(meta.targetAnnotation()), ann);
            }
        }

        boolean changed = false;
        for (AnnotationNode ann : plist) {
            if (ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) continue; // scan only non-ZB annotations
            AnnotationNode zbAnn = annMap.get(ann.desc);
            if (zbAnn == null) continue;

            changed |= resolveParamAnn(paramName, ann, zbAnn);
        }

        return changed;
    }

    private static boolean resolveParamAnn(String paramName, AnnotationNode bbAnn, AnnotationNode zbAnn) {
        var ai = AnnCache.get(zbAnn.desc);
        if (ai == null) return false;

        boolean changed = false;

        for (var elem : ai.td().getDeclaredMethods().asDefined()) {
            var elemAnns = elem.getDeclaredAnnotations();

            var flags_ = elemAnns.ofType(Patch.Internal.Flags.class);
            if (flags_ != null)
                changed |= processFlags(bbAnn, elem.getName(), flags_.load(), paramName);
        }

        changed |= applyMapBoolFromZbAnn(bbAnn, zbAnn);

        return changed;
    }

    /** Applies {@link Patch.Internal.MapBool} rules from the ZombieBuddy annotation type onto the paired ByteBuddy annotation node. */
    private static boolean applyMapBoolFromZbAnn(AnnotationNode bbAnn, AnnotationNode zbAnn) {
        var ai = AnnCache.get(zbAnn.desc);
        if (ai == null) return false;

        boolean changed = false;
        for (var elem : ai.td().getDeclaredMethods().asDefined()) {
            var mapBool_ = elem.getDeclaredAnnotations().ofType(Patch.Internal.MapBool.class);
            if (mapBool_ != null)
                changed |= processMapBool(bbAnn, elem.getName(), mapBool_.load());
        }

        return changed;
    }

    private static boolean processMapBool(AnnotationNode bbAnn, String elemName, Patch.Internal.MapBool mapBool) {
        Boolean bValue = getAnnElem(bbAnn, elemName, Boolean.class);
        if (bValue == null) return false;

        bbAnn.visit(elemName, mapBoolEncodedClassLiteral(mapBool, bValue));
        return true;
    }

    /** ASM runtime annotation {@code Class} values use {@link Type}; {@code void.class} default is {@link Type#VOID_TYPE} ({@code default_value: class V}). */
    private static Type mapBoolEncodedClassLiteral(Patch.Internal.MapBool mapBool, boolean bValue) {
        if (bValue)
            return Type.getType(mapBool.onTrue());

        Class<?> onFalse = mapBool.onFalse();
        return onFalse == Patch.Internal.DropAnnParam.class ? Type.VOID_TYPE : Type.getType(onFalse);
    }

    private static boolean processFlags(AnnotationNode bbAnn, String elemName, Patch.Internal.Flags flags, String paramName) {
        boolean changed = false;

        if (flags.inferFromTargetName()) {
            if (bbAnn.values == null) {
                bbAnn.visit(elemName, paramName);
                changed = true;
            }
        }

        if (flags.probeField()) {
            List<?> values = getAnnElem(bbAnn, elemName, List.class);
            if (values != null && values.size() == 1) {
                bbAnn.visit(elemName, values.get(0));
                changed = true;
            }
        }

        return changed;
    }

    private static <T> T getAnnElem(AnnotationNode ann, String name, Class<T> type) {
        if (ann.values == null) return null;

        for (int i = 0, n = ann.values.size(); i < n; i += 2) {
            if (name.equals(ann.values.get(i))) {
                Object val = ann.values.get(i + 1);

                return type.isInstance(val)
                    ? type.cast(val)
                    : null;
            }
        }

        return null;
    }
}
