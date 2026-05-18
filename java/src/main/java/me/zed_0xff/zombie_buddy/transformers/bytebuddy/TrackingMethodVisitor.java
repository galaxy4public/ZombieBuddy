package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;

class TrackingMethodVisitor extends MethodVisitor implements ScopeVisitor {
    protected final ScopeTracker<Object> tracker;
    private ScopeTracker.Scope mthScope;

    public TrackingMethodVisitor(int api, MethodVisitor mv, ScopeTracker<Object> tracker) {
        this(api, mv, tracker, null, null);
    }

    /** When {@code methodName} and {@code methodDescriptor} are non-null, pushes {@link ScopeTracker.Mth} until {@link #visitEnd}. */
    public TrackingMethodVisitor(int api, MethodVisitor mv, ScopeTracker<Object> tracker, String methodName, String methodDescriptor) {
        super(api, mv);
        this.tracker = tracker;
        if (methodName != null && methodDescriptor != null) {
            mthScope = tracker.enter(new ScopeTracker.Mth(methodName, methodDescriptor));
        }
    }

    @Override
    public ScopeTracker<Object> getTracker() { return tracker; }

    @Override
    public void visitEnd() {
        try {
            super.visitEnd();
        } finally {
            if (mthScope != null) {
                mthScope.close();
                mthScope = null;
            }
        }
    }

    private AnnotationVisitor wrapSubtree(AnnotationVisitor av, ScopeTracker.Node scopeNode) {
        ScopeTracker.Scope s = tracker.enter(scopeNode);
        AnnotationVisitor body = av == null ? null : new TrackingAnnotationVisitor(api, av, tracker);

        return ScopeVisitor.closingDelegate(api, body, s);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return wrapSubtree(super.visitAnnotation(desc, visible), new ScopeTracker.Ann(desc));
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int index, String desc, boolean visible) {
        return wrapSubtree(super.visitParameterAnnotation(index, desc, visible), new ScopeTracker.Arg(index, desc));
    }
}
