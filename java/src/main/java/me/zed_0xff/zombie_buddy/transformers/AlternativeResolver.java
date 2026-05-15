package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;

import net.bytebuddy.jar.asm.AnnotationVisitor;

import java.util.function.BiFunction;
import java.util.List;

/*
 * resolves alternative names like Field("CHUNKS_PER_WIDTH", "ChunksPerWidth") to the first one that exists in the target class
 */
public class AlternativeResolver extends AbstractAnnotationTransformer {
    @Override
    protected Object resolveArray(AnnSite site, String annDesc, String name, List<Object> values) {
        return SKIP;
    }
}
