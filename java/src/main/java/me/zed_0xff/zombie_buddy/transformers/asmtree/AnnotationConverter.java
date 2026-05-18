package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.Patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * For each {@code @Patch.*} annotation that declares {@link Patch.Internal.Meta}, appends the matching target annotation
 * (same visibility list; element values shallow-copied, optional {@link Patch.Internal.Meta#targetParamNames()} rename).
 * ZombieBuddy annotations are left in place.
 */
public class AnnotationConverter extends AbstractTransformer {

    @Override
    protected void transformNode(ClassNode cn) {
        Patch patch = m_ctx.getPatch();
        if (patch == null) return;

        boolean patchAdvice = patch.isAdvice();
        Map<String, PatchAnnMeta[]> cache = patchAnnMetaByDesc();

        boolean changed = false;
        changed |= convertAnnList(cn.visibleAnnotations, patchAdvice, cache);
        changed |= convertAnnList(cn.invisibleAnnotations, patchAdvice, cache);

        for (FieldNode fn : cn.fields) {
            changed |= convertAnnList(fn.visibleAnnotations, patchAdvice, cache);
            changed |= convertAnnList(fn.invisibleAnnotations, patchAdvice, cache);
        }

        for (MethodNode mn : cn.methods) {
            changed |= convertAnnList(mn.visibleAnnotations, patchAdvice, cache);
            changed |= convertAnnList(mn.invisibleAnnotations, patchAdvice, cache);
            changed |= convertParameterAnnLists(mn.visibleParameterAnnotations, patchAdvice, cache);
            changed |= convertParameterAnnLists(mn.invisibleParameterAnnotations, patchAdvice, cache);
        }

        if (changed) setChanged();
    }

    private static boolean convertParameterAnnLists(List<AnnotationNode>[] lists, boolean patchAdvice, Map<String, PatchAnnMeta[]> cache) {
        if (lists == null) return false;

        boolean changed = false;
        for (List<AnnotationNode> plist : lists) {
            if (plist == null) continue;

            changed |= convertAnnList(plist, patchAdvice, cache);
        }

        return changed;
    }

    private static boolean convertAnnList(List<AnnotationNode> list, boolean patchAdvice, Map<String, PatchAnnMeta[]> cache) {
        if (list == null || list.isEmpty()) return false;

        List<AnnotationNode> snapshot = List.copyOf(list);
        List<AnnotationNode> toAdd = new ArrayList<>();
        for (AnnotationNode ann : snapshot) {
            PatchAnnMeta[] rules = cache.get(ann.desc);
            if (rules == null) continue;

            PatchAnnMeta rule = pick(rules, patchAdvice);
            if (rule == null) continue;

            if (containsDesc(list,  rule.targetDesc())) continue;
            if (containsDesc(toAdd, rule.targetDesc())) continue;

            toAdd.add(translated(ann, rule));
        }

        if (toAdd.isEmpty()) return false;

        list.addAll(toAdd);

        return true;
    }

    private static PatchAnnMeta pick(PatchAnnMeta[] rules, boolean patchAdvice) {
        for (PatchAnnMeta r : rules) {
            if (r.advice() != patchAdvice) continue;
            return r;
        }
        return null;
    }

    private static boolean containsDesc(List<AnnotationNode> list, String desc) {
        for (AnnotationNode a : list) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static AnnotationNode translated(AnnotationNode src, PatchAnnMeta rule) {
        AnnotationNode dst = new AnnotationNode(rule.targetDesc());
        if (src.values != null && !src.values.isEmpty()) {
            List<Object> vals = new ArrayList<>(src.values);
            applyTargetParamRename(vals, rule.targetParamNames(), rule.targetParamValues());
            dst.values = vals;
        }
        return dst;
    }

    private static void applyTargetParamRename(List<Object> vals, String[] names, String[] values) {
        if (names == null || names.length == 0 || vals == null || vals.isEmpty()) return;

        int n = values == null ? 0 : Math.min(names.length, values.length);
        if (n == 0) return;

        for (int i = 0; i < n; i++) {
            renameKey(vals, names[i], values[i]);
        }
    }

    private static void renameKey(List<Object> vals, String from, String to) {
        for (int i = 0; i < vals.size(); i += 2) {
            Object k = vals.get(i);
            if (!(k instanceof String s)) continue;
            if (from.equals(s))
                vals.set(i, to);
        }
    }
}
