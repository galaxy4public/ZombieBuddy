package me.zed_0xff.zombie_buddy.transformers;

import me.zed_0xff.zombie_buddy.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.jar.JarFile;
import java.util.Map;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.ClassFileLocator.*;

/**
 * Locator + {@link TypePool} layering for one jar (or test slice). {@link #setClassBytes(String, byte[])} keys and {@link ClassFileLocator#locate} names use the JVM <strong>binary name</strong> ({@link Class#getName()} style, {@code pkg.Outer$Inner}).
 * {@link #m_origPool} is tied to {@link #m_origLocator}; {@link #m_curPool} is recreated whenever overlays change so it stays aligned with {@link #getCurLocator()}.
 */
public class JarContext implements Closeable {
    private final ClassFileLocator    m_origLocator;
    private final TypePool            m_origPool;
    private final Map<String, byte[]> m_newClassBytes = new HashMap<>(); // className → new class bytes

    private ClassFileLocator          m_curLocator = null;
    private TypePool                  m_curPool = null;

    private JarContext(ClassFileLocator locator) {
        m_origLocator = new Compound(
                locator,
                ForClassLoader.of(JarContext.class.getClassLoader()) // ZB + JDK
                );
        // Pool must match m_origLocator: tests pass a Simple locator with one class; nested/referenced types and annotation types resolve via the classloader leg.
        m_origPool = TypePool.Default.of(m_origLocator);
    }

    public static JarContext forJar(JarFile jar) {
        return new JarContext(new ForJarFile(jar));
    }

    public static JarContext forClass(String className, byte[] classBytes) {
        return new JarContext(new Simple(Map.of(className, classBytes)));
    }

    @Override
    public void close() throws IOException {
        m_origLocator.close();
        // m_curLocator doesn't need to be closed
    }

    public ClassFileLocator getCurLocator() {
        if (m_newClassBytes.isEmpty()) {
            return m_origLocator;
        }
        if (m_curLocator == null) {
            m_curLocator = new Compound(
                new Simple(m_newClassBytes),
                m_origLocator
            );
        }
        return m_curLocator;
    }

    private TypePool getCurTypePool() {
        if (m_newClassBytes.isEmpty()) {
            return m_origPool;
        }
        if (m_curPool == null) {
            m_curPool = TypePool.Default.of(getCurLocator());
        }
        return m_curPool;
    }

    public byte[] getClassBytes(String className) {
        if (m_newClassBytes.containsKey(className)) {
            return m_newClassBytes.get(className);
        }

        try {
            var res = getCurLocator().locate(className);
            if (!res.isResolved()) {
                Logger.once.warn("Failed to locate class bytes for", className);
                return null;
            }
            return res.resolve();
        } catch (IOException e) {
            Logger.once.warn("IOException while locating class bytes for", className, e);
            return null;
        }
    }

    public void setClassBytes(String className, byte[] classBytes) {
        m_newClassBytes.put(className, classBytes);
        m_curLocator = null;
        m_curPool = null;
    }

    /*
     * accepts various forms of type names:
     *  - descriptor: Ljava/lang/String;
     *  - binary name: java.lang.String
     *  - internal name: java/lang/String
     *  - primitive or array descriptor: [I, [Ljava/lang/String;
     */
    public TypeDescription getTypeDesc(String s) {
        return getTypeDesc(s, getCurTypePool());
    }

    public TypeDescription getOrigTypeDesc(String s) {
        return getTypeDesc(s, m_origPool);
    }

    private TypeDescription getTypeDesc(String s, TypePool pool) {
        try {
            if (s == null || s.isEmpty()) {
                throw new IllegalArgumentException("empty type");
            }

            // descriptor: Ljava/lang/String;
            if (s.charAt(0) == 'L' && s.endsWith(";")) {
                return pool.describe(s).resolve();
            }

            // primitive or array descriptor: [I, [Ljava/lang/String;
            if (s.charAt(0) == '[' || s.length() == 1) {
                return pool.describe(s).resolve();
            }

            // internal name: java/lang/String
            if (s.indexOf('/') >= 0) {
                return pool.describe(s.replace('/', '.')).resolve();
            }

            // binary name: java.lang.String
            return pool.describe(s).resolve();
        } catch (Exception e) {
            Logger.once.warn("Failed to resolve type", s, e.getMessage());
            return null;
        }
    }
}
