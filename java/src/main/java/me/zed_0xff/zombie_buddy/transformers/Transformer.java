package me.zed_0xff.zombie_buddy.transformers;

public abstract class Transformer {
    protected static final Result NOOP_RESULT = new Result(null, false);

    public record Result(byte[] bytes, boolean modified) {}

    protected ClassContext m_ctx;
    private boolean m_modified;    // means THIS transformer has made changes, not the whole chain as m_ctx.setChanged does

    protected void setModified() {
        m_modified = true;
        m_ctx.setChanged();
    }

    protected boolean isModified() {
        return m_modified;
    }

    public abstract Result transform(byte[] classBytes, ClassContext ctx);
}
