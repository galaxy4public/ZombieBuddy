package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.jar.asm.Opcodes;

public interface Transformer {
    public static final int ASM_API = Opcodes.ASM9;

    public record Result(byte[] bytes, boolean modified) {}

    Result transform(byte[] classBytes);
}
