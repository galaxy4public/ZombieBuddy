package me.zed_0xff.zombie_buddy.transformers;

import java.io.IOException;

public abstract class AbstractTest {
    protected static byte[] getClassBytes(Class<?> cls) throws IOException {
        String path = "/" + cls.getName().replace('.', '/') + ".class";

        try (var in = cls.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    public static class TestClassContext extends ClassContext {
        private byte[] m_bytes;

        public TestClassContext(Class<?> cls) throws IOException {
            super(cls.getName(), JarContext.forClass(cls.getName(), getClassBytes(cls)));
            m_bytes = getClassBytes(cls);
        }

        public byte[] getBytes() { return m_bytes; }
    }
}
