package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Internal.Meta;

import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.description.type.TypeDescription;
import static org.objectweb.asm.Type.getDescriptor;

public class AnnCache {
    private static final Map<String, AnnInfo> _cache = new HashMap<>();

    public record AnnInfo(
            Class<?> cls, 
            TypeDescription td, 
            Meta[] metas
    ) {
        public Meta getMeta(boolean isAdvice) {
            for (Meta meta : metas) {
                if (meta.isAdvice() == isAdvice) {
                    return meta;
                }
            }
            return null;
        }
    }

    static {
        for (Class<?> c : Patch.class.getDeclaredClasses()) {
            if (!c.isAnnotation()) continue;

            _cache.put(
                    getDescriptor(c),
                    new AnnInfo(
                        c,
                        TypeDescription.ForLoadedType.of(c),
                        c.getDeclaredAnnotationsByType(Meta.class)
                    )
            );
        }
    }
    
    public static AnnInfo get(String desc) {
        return _cache.get(desc);
    }

    public static Meta getMeta(String desc, boolean isAdvice) {
        AnnInfo ai = get(desc);
        return (ai == null) ? null : ai.getMeta(isAdvice);
    }
}
