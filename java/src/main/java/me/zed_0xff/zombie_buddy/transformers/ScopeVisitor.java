package me.zed_0xff.zombie_buddy.transformers;

import net.bytebuddy.jar.asm.AnnotationVisitor;

import java.util.function.Supplier;

interface ScopeVisitor {
    ScopeTracker<Object> getTracker();

    /**
     * Wraps {@code delegate} so {@link AnnotationVisitor#visitEnd} closes {@code scope}. If {@code delegate} is {@code null}, closes {@code scope} and returns {@code null}.
     */
    static AnnotationVisitor closingDelegate(int api, AnnotationVisitor delegate, ScopeTracker.Scope scope) {
        if (delegate == null) {
            scope.close();

            return null;
        }

        return new AnnotationVisitor(api, delegate) {
            @Override
            public void visitEnd() {
                try {
                    super.visitEnd();
                } finally {
                    scope.close();
                }
            }
        };
    }

    /** Keeps {@link ScopeTracker.Ann} on the stack until the annotation visitor finishes ({@code visitEnd}), unlike {@link #scoped(Object, Supplier)}. */
    static AnnotationVisitor wrapAnn(int api, ScopeTracker<Object> tracker, String desc, AnnotationVisitor av) {
        ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Ann(desc));

        return closingDelegate(api, av, s);
    }

    /** Keeps {@link ScopeTracker.Arg} on the stack until the parameter annotation visitor finishes. */
    static AnnotationVisitor wrapArg(int api, ScopeTracker<Object> tracker, int index, String desc, AnnotationVisitor av) {
        ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Arg(index, desc));

        return closingDelegate(api, av, s);
    }

    default <R> R scoped(Object node, Supplier<R> fn) {
        try (var s = getTracker().enter(node)) {
            return fn.get();
        }
    }

    default void scoped(Object node, Runnable fn) {
        try (var s = getTracker().enter(node)) {
            fn.run();
        }
    }
}
