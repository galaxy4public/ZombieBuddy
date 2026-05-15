package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class AlternativeResolver extends AbstractAnnotationTransformer {
    private static final Map<String, String> ANN_MAP = Map.of(
        Type.getDescriptor(Advice.FieldValue.class), "value" // assuming there can be only one param per class
    );

    @Override
    protected Object resolveArray(AnnSite site, String annDesc, String name, List<Object> values) {
        String paramName = ANN_MAP.get(annDesc);
        if (paramName != null && paramName.equals(name)) {
            if (values.size() == 1) return values.get(0);
        }
        return SKIP;
    }
}
