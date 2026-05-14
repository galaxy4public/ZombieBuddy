package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Utils;

import java.lang.reflect.Modifier;

import net.bytebuddy.jar.asm.*;

public class ClassDumper {
    private final byte[] m_bytes;
    private final ClassReader m_reader;

    public ClassDumper(byte[] classBytes) {
        m_bytes = classBytes;
        m_reader = new ClassReader(classBytes);
    }

    public String dumpFields() {
        StringBuilder sb = new StringBuilder();
        m_reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                sb.append(String.format("%s %s %s\n", Modifier.toString(access), name, descriptor));
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, 0);
        return sb.toString();
    }

    public String dumpMethods() {
        StringBuilder sb = new StringBuilder();
        m_reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                sb.append(String.format("%s %s %s\n", Modifier.toString(access), descriptor, name));
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return sb.toString();
    }

    public String indent(String str) {
        if (Utils.isBlank(str)) {
            return "";
        }
        return "    " + str.replace("\n", "\n    ");
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(m_reader.getClassName()).append("\n");
        sb.append(indent(dumpFields()));
        sb.append(indent(dumpMethods()));
        return sb.toString();
    }
}
