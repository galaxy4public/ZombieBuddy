package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Internal.Meta;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.method.MethodDescription;

/*
 * base class for transformers that rewrite patch annotations (e.g. AlternativeResolver, AnnotationConverter)
 */
public abstract class AbstractPatchAnnotationTransformerV2 extends Transformer {
    static boolean isZBdesc(String desc) {
        return desc != null && desc.startsWith("Lme/zed_0xff/zombie_buddy/");
    }

    /** @return loaded {@link Patch.Internal.Flags} on {@code method}, or {@code null} if absent */
    protected static Patch.Internal.Flags getMethodFlags(MethodDescription method) {
        AnnotationDescription.Loadable<Patch.Internal.Flags> ld = method
            .getDeclaredAnnotations()
            .ofType(Patch.Internal.Flags.class);
            
        return ld == null ? null : ld.load();
    }

    record MetaInfo(Meta meta, AnnotationDescription annDesc) {}

    MetaInfo getMetaInfo(AnnotationSource src) {
        Patch patch = m_ctx.getPatch();
        if (patch == null) return null;

        boolean patchAdvice = patch.isAdvice();
        for (AnnotationDescription zb : src.getDeclaredAnnotations().filter(a -> isZBdesc(a.getAnnotationType().getDescriptor()))) {
            for (AnnotationDescription metaAnn : zb.getAnnotationType().getDeclaredAnnotations().filter(ann -> ann.getAnnotationType().represents(Meta.class))) {
                Patch.Internal.Meta meta = metaAnn.prepare(Meta.class).load();
                if (meta.isAdvice() == patchAdvice) {
                    return new MetaInfo(meta, zb);
                }
            }
        }

        return null;
    }
}
