package me.zed_0xff.zombie_buddy.transformers;

import java.io.IOException;

abstract class AbstractTest {
    protected static byte[] getClassBytes(Class<?> cls) throws IOException {
        String path = "/" + cls.getName().replace('.', '/') + ".class";

        try (var in = cls.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
