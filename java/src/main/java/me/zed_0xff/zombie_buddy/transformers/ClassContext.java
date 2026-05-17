package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Patch;

import java.util.Objects;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

/** Per-class view into a shared {@link JarContext}. Prefer one instance per {@code className} while mutating that jar slice; cached {@link #getCurrentTypeDesc()} can drift if the same name is updated through another {@code ClassContext} sharing {@code jctx}. */
public class ClassContext {
    private final String          m_className;
    private final TypeDescription m_origDesc;
    private final JarContext      m_jctx; // shared

    // mutable
    private boolean               m_annChanged;
    private boolean               m_changed;
    private TypeDescription       m_typeDesc;
    private AnnotationDescription m_patch = null; // lazily initialized by getPatch()

    /**
     * @param className JVM binary name ({@link Class#getName()})
     * @param classBytes must match what {@link JarContext} resolves for {@code className}
     */
    public ClassContext(String className, JarContext jctx) {
        m_className = className;
        m_origDesc  = jctx.getOrigTypeDesc(className);
        m_typeDesc  = null;
        m_jctx      = jctx;
    }

    @Override
    public String toString() {
        String simpleName = m_className.replaceAll(".*[.$]", ".");
        return "ClassContext(" + simpleName + ")";
    }

    public String className() { return m_className; }

    public void setClassBytes(byte[] classBytes) {
        m_jctx.setClassBytes(m_className, classBytes);
        setChanged();
        m_typeDesc = null;
    }

    // before any transformations; used for comparison and to access original annotations
    public TypeDescription getOriginalTypeDesc() { return m_origDesc; }
    public TypeDescription getCurrentTypeDesc() {
        if (m_typeDesc == null) {
            m_typeDesc = m_jctx.getTypeDesc(m_className);
        }
        return m_typeDesc;
    }

    public void setAnnChanged()   { m_annChanged = true; } // no way to un-change
    public boolean isAnnChanged() { return m_annChanged; }

    public void setChanged()   { m_changed = true; }       // same
    public boolean isChanged() { return m_changed; }

    public MethodDescription getMethod(String name) {
        var match = getCurrentTypeDesc().getDeclaredMethods().filter(m -> m.getName().equals(name));
        if (match.size() == 0) return null;
        if (match.size() == 1) return match.getOnly();

        Logger.warn("Multiple methods found. Returning first match for", name, m_className);
        return match.get(0);
    }

    public AnnotationDescription getPatch() {
        if (m_patch != null) return m_patch;
            
        TypeDescription td = getOriginalTypeDesc();
        while (td != null) {
            var anns = td.getDeclaredAnnotations().filter(a -> a.getAnnotationType().represents(Patch.class));
            if (!anns.isEmpty()) {
                m_patch = anns.getOnly();
                return m_patch;
            }

            td = td.getEnclosingType();
        }
        return null;
    }

    /** intentionally lookup original type desc only */
    public TypeDescription getPatchTarget() {
        String targetClass = getPatch().getValue("className").resolve(String.class);
        return m_jctx.getOrigTypeDesc(targetClass);
    }
}
