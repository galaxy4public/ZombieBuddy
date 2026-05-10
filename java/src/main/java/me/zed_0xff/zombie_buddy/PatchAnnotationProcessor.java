package me.zed_0xff.zombie_buddy;

import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes({
    "me.zed_0xff.zombie_buddy.Patch.Field",
    "me.zed_0xff.zombie_buddy.Patch.MemberHandle",
    "me.zed_0xff.zombie_buddy.Patch.StaticFieldAlias",
    "me.zed_0xff.zombie_buddy.Patch.StaticFieldAliasRW"
})
public class PatchAnnotationProcessor extends AbstractProcessor {
    @Override
    public javax.lang.model.SourceVersion getSupportedSourceVersion() { return javax.lang.model.SourceVersion.latestSupported(); }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            String name = annotation.getQualifiedName().toString();
            for (Element elem : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (name.endsWith(".Field"))                 processField(elem);
                else if (name.endsWith(".MemberHandle"))     processMemberHandle(elem);
                else if (name.contains(".StaticFieldAlias")) processStaticFieldAlias(elem);
            }
        }
        return true;
    }

    private void processField(Element elem) {
        if (!(elem instanceof VariableElement param)) return;
        for (AnnotationMirror mirror : elem.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch.Field")) continue;
            Map<? extends ExecutableElement, ? extends AnnotationValue> vals = mirror.getElementValues();

            boolean hasValue = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("value"));
            boolean hasName  = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("name"));
            if (hasValue && hasName) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.Field: specify either 'value' or 'name', not both", elem, mirror);
            }

            boolean readOnlyFalse = vals.entrySet().stream()
                .anyMatch(e -> e.getKey().getSimpleName().contentEquals("readOnly") && Boolean.FALSE.equals(e.getValue().getValue()));

            if (!readOnlyFalse && !param.getModifiers().contains(Modifier.FINAL)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "@Patch.Field readOnly=true; declare the parameter final OR set readOnly=false OR use @Patch.FieldRW", elem, mirror);
            }
        }
    }

    private void processMemberHandle(Element elem) {
        if (!(elem instanceof VariableElement field)) return;
        boolean isParam = field.getKind() == javax.lang.model.element.ElementKind.PARAMETER;
        String typeName = field.asType().toString();
        boolean isMethodHandle = typeName.equals("java.lang.invoke.MethodHandle");
        boolean isVarHandle    = typeName.equals("java.lang.invoke.VarHandle");
        if (!isMethodHandle && !isVarHandle) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@Patch.MemberHandle " + (isParam ? "parameter" : "field") + " must be of type MethodHandle or VarHandle", elem);
        }
        if (!isParam) {
            if (!field.getModifiers().contains(Modifier.PUBLIC)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "@Patch.MemberHandle field should be public; inlined advice in a different package will throw IllegalAccessError", elem);
            }
            if (field.getModifiers().contains(Modifier.FINAL)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.MemberHandle field must not be final", elem);
            }
        }
        Element enclosing = isParam ? field.getEnclosingElement().getEnclosingElement() : field.getEnclosingElement();
        boolean hasPatch = enclosing.getAnnotationMirrors().stream()
            .anyMatch(m -> m.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch"));
        if (!hasPatch) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.MemberHandle can only be used in a class annotated with @Patch", elem);
        }
        for (AnnotationMirror mirror : elem.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch.MemberHandle")) continue;
            Map<? extends ExecutableElement, ? extends AnnotationValue> vals = mirror.getElementValues();
            boolean hasValue          = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("value"));
            boolean hasName           = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("name"));
            boolean hasClassName      = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("className"));
            boolean hasOwner          = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("owner"));
            boolean hasReturnType     = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("returnType"));
            boolean hasParameterTypes = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("parameterTypes"));
            boolean hasType           = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("type"));
            if (hasValue && hasName) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.MemberHandle: specify either 'value' or 'name', not both", elem, mirror);
            }
            if (hasClassName && hasOwner) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.MemberHandle: specify either 'className' or 'owner', not both", elem, mirror);
            }
            if (isMethodHandle && (!hasReturnType || !hasParameterTypes)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.MemberHandle on MethodHandle: returnType and parameterTypes must both be specified", elem, mirror);
            }
            if (isVarHandle && !hasType) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.MemberHandle on VarHandle: type must be specified", elem, mirror);
            }
        }
    }

    private void processStaticFieldAlias(Element elem) {
        if (!(elem instanceof VariableElement field)) return;
        if (field.getModifiers().contains(Modifier.FINAL)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias[RW] field must not be final", elem);
        }
        boolean hasPatch = field.getEnclosingElement().getAnnotationMirrors().stream()
            .anyMatch(m -> m.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch"));
        if (!hasPatch) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias[RW] can only be used in a class annotated with @Patch", elem);
        }
    }


}
