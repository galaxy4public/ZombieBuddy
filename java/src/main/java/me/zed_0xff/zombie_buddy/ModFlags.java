package me.zed_0xff.zombie_buddy;

public record ModFlags(int value) {
    public static final int MF_NONE = 0;

    public static final int MF_VALID   = 1 << 0;
    public static final int MF_SIGNED  = 1 << 1;
    public static final int MF_ACTIVE  = 1 << 2;
    public static final int MF_PERSIST = 1 << 3;
    public static final int MF_BANNED  = 1 << 4;
    public static final int MF_PRELOAD = 1 << 5;
    public static final int MF_TRUST_AUTHOR = 1 << 6;

    public static final ModFlags EMPTY = new ModFlags(0);

    public boolean has(int flag) {
        return (value & flag) != 0;
    }

    public boolean hasAll(int... flags) {
        for (int flag : flags) {
            if ((value & flag) == 0) return false;
        }
        return true;
    }

    public ModFlags with(int flag) {
        return new ModFlags(value | flag);
    }
}
