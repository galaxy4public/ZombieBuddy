package me.zed_0xff.zombie_buddy.transformers;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.io.IOException;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.jardump.AsmDump;
import net.bytebuddy.description.method.MethodDescription;

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

        public MethodDescription getMethod(String name) {
            var match = getCurrentTypeDesc().getDeclaredMethods().filter(named(name));
            if (match.size() == 0) return null;
            if (match.size() == 1) return match.getOnly();

            Logger.warn("Multiple methods found. Returning first match for", name);
            return match.get(0);
        }

        public String dumpClass(byte[] bytes) {
            AsmDump dumper = new AsmDump(jarContext());
            return dumper.dump(bytes);
        }
    }
}
