package me.zed_0xff.zombie_buddy.transformers;

import java.util.Map;

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
        m_origDesc       = getTypeDesc(classBytes);
        m_typeDesc       = m_origDesc;
    }

    public void updateTypeDesc(byte[] classBytes) {
        this.m_typeDesc = getTypeDesc(classBytes);
    }

    TypeDescription getTypeDesc(byte[] bytes) {
        ClassFileLocator locator = new ClassFileLocator.Simple(Map.of(m_className, bytes));

        return TypePool.Default.of(locator)
            .describe(m_className)
            .resolve();
    }

    // before any transformations; used for comparison and to access original annotations
    public TypeDescription getOriginalTypeDesc() { return this.m_origDesc; }

    public TypeDescription getTypeDesc() { return this.m_typeDesc; }
    public TypePool        getTypePool() { return this.m_pool; }

    public void setAnnChanged() { this.m_annChanged = true; } // no way to un-change
    public boolean isAnnChanged() { return this.m_annChanged; }

    public void setChanged() { this.m_changed = true; }       // same
    public boolean isChanged() { return this.m_changed; }
}
