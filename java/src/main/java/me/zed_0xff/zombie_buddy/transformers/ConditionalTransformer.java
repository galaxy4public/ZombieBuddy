package me.zed_0xff.zombie_buddy.transformers;

import java.util.function.Supplier;

/*
 * A Publicizer that only publicizes members if the previous transformers made changes to annotations.
 */
public class ConditionalTransformer extends Transformer {
    private final Supplier<? extends Transformer> factory;

    public ConditionalTransformer(Supplier<? extends Transformer> factory) {
        this.factory = factory;
    }

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        return ctx.isAnnChanged()
            ? factory.get().transform(classBytes, ctx)
            : NOOP_RESULT;
    }
}
