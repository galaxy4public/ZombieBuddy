package me.zed_0xff.zombie_buddy.transformers;

public class NoopTransformer implements Transformer {
    static final Result NOOP_RESULT = new Result(null, false);

    public Result transform(byte[] classBytes) {
        return NOOP_RESULT;
    }
}
