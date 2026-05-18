package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import net.bytebuddy.jar.asm.*;

class TrackingClassVisitor extends ClassVisitor implements ScopeVisitor {
    protected final ScopeTracker<Object> tracker;
    private ScopeTracker.Scope clsScope;

    public TrackingClassVisitor(int api, ClassVisitor cv, ScopeTracker<Object> tracker) {
        super(api, cv);
        this.tracker = tracker;
    }

    @Override
    public ScopeTracker<Object> getTracker() { return tracker; }

    /**
     * ASM finishes {@link #visit} before it visits fields/methods; class scope must stay open until {@link #visitEnd},
     * otherwise {@link ScopeTracker.Cls} is popped too early and nested visitors see an empty path.
     */
    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        clsScope = tracker.enter(new ScopeTracker.Cls(name));
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitEnd() {
        try {
            super.visitEnd();
        } finally {
            if (clsScope != null) {
                clsScope.close();
                clsScope = null;
            }
        }
    }
}
