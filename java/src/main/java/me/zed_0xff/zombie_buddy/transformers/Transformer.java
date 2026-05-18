package me.zed_0xff.zombie_buddy.transformers;

public abstract class Transformer {
    protected static final Result NOOP_RESULT = new Result(null, false);

    public record Result(byte[] bytes, boolean modified) {}

    protected ClassContext m_ctx;

    protected void setChanged() { m_ctx.setChanged(); }

    public abstract Result transform(byte[] classBytes, ClassContext ctx);
}
