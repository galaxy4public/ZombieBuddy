package me.zed_0xff.zombie_buddy.transformers;

/*
 * A Publicizer that only publicizes members if the previous transformers made changes to annotations.
 */
public class ConditionalPublicizer extends Publicizer {
    @Override
    public Transformer.Result transform(byte[] classBytes, ClassContext ctx) {
        if (ctx.isAnnChanged()) {
            return super.transform(classBytes, ctx);
        } else {
            return NOOP_RESULT;
        }
    }
}
