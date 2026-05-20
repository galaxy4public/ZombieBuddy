package me.zed_0xff.zombie_buddy.transformers.asmtree;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.AnnCache;
import net.bytebuddy.description.type.TypeDescription;

/*
 * Rewrites annotations using {@link Patch.Internal.MapBool} and parameter {@link Patch.Internal.Flags} (see {@link #resolveMethodParams}).
 * Pairs ZombieBuddy {@code @Patch.*} with same-list ByteBuddy targets when present; otherwise applies in-place on the ZombieBuddy node.
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
        return applyZbPairing(mn.visibleAnnotations, m_ctx.getPatch().isAdvice(), Resolver::applyMapBoolFromZbAnn);
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

        boolean isAdvice = m_ctx.getPatch().isAdvice();
        return applyZbPairing(plist, isAdvice, (bb, zb) -> resolveParamAnn(paramName, bb, zb));
    }

    /**
     * For each {@code @Patch.*} with {@link AnnCache#getMeta}, pairs a same-list ByteBuddy annotation with the same target descriptor when present;
     * otherwise runs {@code onPair} on the ZombieBuddy annotation in-place ({@code bb == zb}).
     */
    private static boolean applyZbPairing(List<AnnotationNode> list, boolean isAdvice, BiPredicate<AnnotationNode, AnnotationNode> onPair) {
        if (Utils.isBlank(list)) return false;

        Map<String, AnnotationNode> zbByTarget = zbAnnByTargetDesc(list, isAdvice);
        if (zbByTarget.isEmpty()) return false;

        HashSet<AnnotationNode> pairedZb = new HashSet<>();
        boolean changed = false;

        for (AnnotationNode ann : list) {
            if (ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) continue;

            AnnotationNode zbAnn = zbByTarget.get(ann.desc);
            if (zbAnn == null) continue;

            pairedZb.add(zbAnn);
            changed |= onPair.test(ann, zbAnn);
        }

        for (AnnotationNode zbAnn : zbByTarget.values()) {
            if (!pairedZb.contains(zbAnn))
                changed |= onPair.test(zbAnn, zbAnn);
        }

        return changed;
    }

    private static Map<String, AnnotationNode> zbAnnByTargetDesc(List<AnnotationNode> list, boolean isAdvice) {
        Map<String, AnnotationNode> zbByTarget = new HashMap<>();
        for (AnnotationNode ann : list) {
            if (!ann.desc.startsWith(Patch.Internal.ANN_PREFIX)) continue;

            var meta = AnnCache.getMeta(ann.desc, isAdvice);
            if (meta == null || meta.targetAnnotation() == void.class) continue;

            zbByTarget.put(Type.getDescriptor(meta.targetAnnotation()), ann);
        }

        return zbByTarget;
    }

    private boolean resolveParamAnn(String paramName, AnnotationNode bbAnn, AnnotationNode zbAnn) {
        var ai = AnnCache.get(zbAnn.desc);
        if (ai == null) return false;

        boolean changed = false;

        for (var elem : ai.td().getDeclaredMethods().asDefined()) {
            var elemAnns = elem.getDeclaredAnnotations();

            var flags_ = elemAnns.ofType(Patch.Internal.Flags.class);
            if (flags_ != null) {
                Patch.Internal.Flags flags = flags_.load();
                String targetElement = flags.targetElement();
                if (Utils.isBlank(targetElement)) targetElement = elem.getName();
                changed |= processFlags(bbAnn, targetElement, flags, paramName);
            }
        }

        changed |= applyMapBoolFromZbAnn(bbAnn, zbAnn);
        return changed;
    }

    /** Applies {@link Patch.Internal.MapBool} metadata from {@code zbAnn}'s type onto {@code bbAnn} (ByteBuddy copy when present, or the ZombieBuddy node in-place when {@code bbAnn == zbAnn}). */
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
        Boolean bValue = AnnElements.fromValues(bbAnn.values).getBoolean(elemName);
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

    private boolean processFlags(AnnotationNode bbAnn, String elemName, Patch.Internal.Flags flags, String paramName) {
        AnnElements els = AnnElements.fromValues(bbAnn.values);
        boolean changed = false;

        if (flags.inferFromTargetName() && Utils.isBlank(els.get(elemName))) {
            bbAnn.visit(elemName, paramName);
            changed = true;
        }

        while (flags.probeField()) {
            Object raw = els.get(elemName);
            if (!(raw instanceof List<?> values) || Utils.isBlank(values)) break;

            if (values.size() == 1) {
                // trivial case, just one value to fill in, no need to resolve, just convert String[] -> String
                bbAnn.visit(elemName, values.get(0));
                changed = true;
                break;
            }

            TypeDescription td = m_ctx.getPatchTarget();
            if (td == null) {
                Logger.once.warn("cannot find patch target class", m_ctx.getPatch().className());
                break;
            }
            var fields = td.getDeclaredFields();
            for (var f : values) {
                if (!(f instanceof String fieldName)) continue;

                var r = fields.filter(named(fieldName));
                if (r.size() == 1) {
                    bbAnn.visit(elemName, fieldName);
                    changed = true;
                    break;
                }
            }

            break;
        }
        return changed;
    }
}
