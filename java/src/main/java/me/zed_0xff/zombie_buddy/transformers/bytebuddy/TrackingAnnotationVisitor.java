package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import net.bytebuddy.jar.asm.AnnotationVisitor;

class TrackingAnnotationVisitor extends AnnotationVisitor {
    protected final ScopeTracker<Object> tracker;

    public TrackingAnnotationVisitor(int api, ScopeTracker<Object> tracker) {
        super(api);
        this.tracker = tracker;
    }

    public TrackingAnnotationVisitor(int api, AnnotationVisitor av, ScopeTracker<Object> tracker) {
        super(api, av);
        this.tracker = tracker;
    }

    @Override
    public void visit(String name, Object value) {
        try (var s = tracker.enter(new ScopeTracker.Elm(name != null ? name : "value"))) {
            super.visit(name, value);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
        ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Ann(descriptor));
        AnnotationVisitor av = super.visitAnnotation(name, descriptor);
        AnnotationVisitor body = av == null ? null : new TrackingAnnotationVisitor(api, av, tracker);

        return ScopeVisitor.closingDelegate(api, body, s);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        ScopeTracker.Scope s = tracker.enter(new ScopeTracker.Arr(name));
        AnnotationVisitor av = super.visitArray(name);
        AnnotationVisitor body = av == null ? null : new TrackingAnnotationVisitor(api, av, tracker);

        return ScopeVisitor.closingDelegate(api, body, s);
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
    }
}
