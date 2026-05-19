package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.transformers.AnnCache;
import me.zed_0xff.zombie_buddy.transformers.AnnCache.AnnInfo;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Utils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;

/*
 * For ZB {@code @Patch.*} parameter annotations: fills members tagged {@link Patch.Internal.Flags#inferFromTargetName()}
 * when bytecode omits them or supplies an empty {@link String} / {@code String[]} (e.g. {@code @Patch.Field} → {@code value = {paramName}}).
 */
public class Resolver extends AbstractTransformer {
    @Override
    protected boolean transformNode(ClassNode cn) {
        // Logger.debug("Resolver.transformNode", cn.name, m_ctx.getPatch());
        if (m_ctx.getPatch() == null) return false;

        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            changed |= resolveMethodParams(mn);
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
        // Logger.debug("resolveParamAnn", paramName, bbAnn, zbAnn, ai);
        if (ai == null) return false;

        boolean changed = false;

        for (var elem : ai.td().getDeclaredMethods().asDefined()) {
            var flags_ = elem.getDeclaredAnnotations().ofType(Patch.Internal.Flags.class);
            Patch.Internal.Flags flags = flags_ == null ? null : flags_.load();
            // Logger.debug("elem", elem, flags, paramName);
            if (flags == null) continue;

            changed |= processFlags(bbAnn, elem.getName(), flags, paramName);
        }

        return changed;
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
            var values = getAnnElem(bbAnn, elemName);
            if (values != null && values.size() == 1) {
                bbAnn.visit(elemName, values.get(0));
                changed = true;
            }
        }

        return changed;
    }

    private static ArrayList<?> getAnnElem(AnnotationNode ann, String name) {
        if (ann.values == null) return null;

        for (int i = 0; i < ann.values.size(); i += 2) {
            if (name.equals(ann.values.get(i))) {
                if (ann.values.get(i + 1) instanceof ArrayList<?> typedVal) {
                    return typedVal;
                }
                return null;
            }
        }

        return null;
    }
}
