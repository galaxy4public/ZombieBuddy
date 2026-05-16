package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.*;

import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class AlternativeResolver extends AbstractPatchAnnotationTransformer {
    @Override
    protected Object resolveArray(AnnSite site, String annDesc, String name, List<Object> values) {
        Logger.debug("resolveArray", site, annDesc, name, values);

        // String paramName = ?
        // if (paramName != null && paramName.equals(name)) {
        //     if (values.size() == 1) return values.get(0); // trivial case: only one alternative, so no need to check existence
        // }

        // NB: class may or may not be loaded at this point, but TypeDescription is always available
        TypeDescription td = m_ctx.getPatchTargetTypeDesc();
        if (td != null) {
            Logger.debug("target type", td);
        }

        return KEEP; // keep as it is (array)
    }

    // null means keep original
    @Override
    protected AnnotationVisitor visitMappedParamAnnotation(AnnInfo ai, boolean visible, TriFunction<Integer, String, Boolean, AnnotationVisitor> visitor, int pidx, String paramName) {
        return null;
    }

    // null means keep original
    @Override
    protected AnnotationVisitor visitMappedAnnotation(AnnInfo ai, boolean visible, BiFunction<String, Boolean, AnnotationVisitor> visitor) {
        return null;
    }
}
