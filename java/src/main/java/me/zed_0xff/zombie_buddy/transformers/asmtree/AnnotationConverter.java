package me.zed_0xff.zombie_buddy.transformers.asmtree;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Internal.Meta;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.transformers.AnnCache;
import me.zed_0xff.zombie_buddy.transformers.AnnCache.AnnInfo;

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
        if (Utils.isBlank(list)) return false;

        List<AnnotationNode> snapshot = List.copyOf(list);
        List<AnnotationNode> toAdd = new ArrayList<>();
        for (AnnotationNode ann : snapshot) {
            AnnInfo ai = AnnCache.get(ann.desc);
            if (ai == null) continue;

            var translated = translated(ann, ai);
            if (translated == null) continue;

            toAdd.add(translated);
        }
        list.addAll(toAdd);
        return !toAdd.isEmpty();
    }

    // {"name"} => "name"
    private Object transformValue(Object value) {
        if (value == null) return null;

        if (value.getClass().isArray() && Array.getLength(value) == 1) {
            return Array.get(value, 0);
        }

        if (value instanceof List<?> list && list.size() == 1) {
            return list.get(0);
        }
        return value;
    }

    private AnnotationNode translated(AnnotationNode src, AnnInfo ai) {
        Meta meta = ai.getMeta(m_isAdvice);
        if (meta == null) return null;

        AnnotationNode dst = new AnnotationNode(Type.getDescriptor(meta.targetAnnotation()));

        AnnElements els = AnnElements.fromValues(src.values);
        for (var e : els.entrySet()) {
            e.setValue(transformValue(e.getValue()));
        }

        applyTargetParams(els, meta.targetParamNames(), meta.targetParamValues());

        for (var elem : ai.td().getDeclaredMethods().asDefined()) {
            var elemAnns = elem.getDeclaredAnnotations();
            var flags_ = elemAnns.ofType(Patch.Internal.Flags.class);
            if (flags_ == null) continue;

            Patch.Internal.Flags flags = flags_.load();
            if (!Utils.isBlank(flags.targetElement()) && els.containsKey(elem.getName())) {
                els.put(flags.targetElement(), els.remove(elem.getName()));
            }
        }
        dst.values = els.toValues();
        return dst;
    }

    private static void applyTargetParams(AnnElements els, String[] names, String[] values) {
        if (Utils.isBlank(names) || Utils.isBlank(values)) return;

        for(int i = 0; i < names.length && i < values.length; i++) {
            els.put(names[i], values[i]);
        }
    }
}
