package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public class ClassContext {
    private final String          m_className;
    private final TypePool        m_pool;
    private final TypeDescription m_origDesc;

    // mutable
    private boolean         m_annChanged;
    private boolean         m_changed;
    private TypeDescription m_typeDesc;

    public ClassContext(String className, byte[] classBytes, TypePool pool) {
        this.m_className = className;
        this.m_pool      = pool;
        m_origDesc       = buildTypeDesc(classBytes);
        m_typeDesc       = m_origDesc;
    }

    private record ByteKey(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ByteKey bk &&
                Arrays.equals(bytes, bk.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final Map<ByteKey, TypeDescription> _cache = new HashMap<>();

    private TypeDescription buildTypeDesc(byte[] bytes) {
        return _cache.computeIfAbsent(new ByteKey(bytes), k -> {
            ClassFileLocator locator =
                new ClassFileLocator.Compound(
                        new ClassFileLocator.Simple(Map.of(m_className, bytes)),
                        ClassFileLocator.ForClassLoader.ofSystemLoader()
                        );

            return TypePool.Default.of(locator)
                .describe(m_className)
                .resolve();
        });
    }

    public void updateTypeDesc(byte[] classBytes) {
        this.m_typeDesc = buildTypeDesc(classBytes);
    }

    // before any transformations; used for comparison and to access original annotations
    public TypeDescription getOriginalTypeDesc() { return this.m_origDesc; }

    public TypeDescription getTypeDesc() { return this.m_typeDesc; }
    public TypePool        getTypePool() { return this.m_pool; }

    public void setAnnChanged() { this.m_annChanged = true; } // no way to un-change
    public boolean isAnnChanged() { return this.m_annChanged; }

    public void setChanged() { this.m_changed = true; }       // same
    public boolean isChanged() { return this.m_changed; }

    public MethodDescription getMethod(String name) {
        var match = this.m_typeDesc.getDeclaredMethods().filter(m -> m.getName().equals(name));
        if (match.size() == 0) return null;
        if (match.size() == 1) return match.getOnly();

        Logger.warn("Multiple methods found. Returning first match for", name, m_className);
        return match.get(0);
    }
}
