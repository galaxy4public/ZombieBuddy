package me.zed_0xff.zombie_buddy.transformers.asmtree;

import me.zed_0xff.zombie_buddy.transformers.AnnCache;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Type;
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
    private boolean m_isAdvice;

    @Override
    protected boolean transformNode(ClassNode cn) {
        Patch patch = m_ctx.getPatch();
        if (patch == null) return false;

        m_isAdvice = patch.isAdvice();

        boolean changed = convertAnns(cn.visibleAnnotations);

        for (FieldNode fn : cn.fields) {
            changed |= convertAnns(fn.visibleAnnotations); // |= does not short-circuit
        }

        for (MethodNode mn : cn.methods) {
            changed |= convertAnns(mn.visibleAnnotations);
            changed |= convertParamAnns(mn.visibleParameterAnnotations);
        }

        return changed;
    }

    private boolean convertParamAnns(List<AnnotationNode>[] lists) {
        if (lists == null) return false;

        boolean changed = false;
        for (List<AnnotationNode> plist : lists) {
            if (plist == null) continue;
            changed |= convertAnns(plist);
        }
        return changed;
    }

    private boolean convertAnns(List<AnnotationNode> list) {
        if (list == null || list.isEmpty()) return false;

        List<AnnotationNode> snapshot = List.copyOf(list);
        List<AnnotationNode> toAdd = new ArrayList<>();
        for (AnnotationNode ann : snapshot) {
            Patch.Internal.Meta rule = AnnCache.getMeta(ann.desc, m_isAdvice);
            if (rule == null) continue;

            String targetDesc = Type.getDescriptor(rule.targetAnnotation());
            if (containsDesc(list, targetDesc)) continue;
            if (containsDesc(toAdd, targetDesc)) continue;

            toAdd.add(translated(ann, rule));
        }
        list.addAll(toAdd);
        return !toAdd.isEmpty();
    }

    private static boolean containsDesc(List<AnnotationNode> list, String desc) {
        for (AnnotationNode a : list) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static AnnotationNode translated(AnnotationNode src, Patch.Internal.Meta meta) {
        AnnotationNode dst = new AnnotationNode(Type.getDescriptor(meta.targetAnnotation()));
        if (src.values != null && !src.values.isEmpty()) {
            List<Object> vals = new ArrayList<>(src.values);
            applyTargetParams(vals, meta.targetParamNames(), meta.targetParamValues());
            dst.values = vals;
        }
        return dst;
    }

    private static void applyTargetParams(List<Object> vals, String[] names, String[] values) {
        if (Utils.isBlank(names) || Utils.isBlank(values)) return;

        for(int i = 0; i < names.length && i < values.length; i++) {
            vals.add(names[i]);
            vals.add(values[i]);
        }
    }
}
