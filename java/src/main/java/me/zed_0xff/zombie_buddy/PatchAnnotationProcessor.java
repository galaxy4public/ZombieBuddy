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
    "me.zed_0xff.zombie_buddy.Patch.StaticFieldAlias",
    "me.zed_0xff.zombie_buddy.Patch.StaticFieldAliasRW",
    "me.zed_0xff.zombie_buddy.Patch.Trampoline"
})
public class PatchAnnotationProcessor extends AbstractProcessor {
    @Override
    public javax.lang.model.SourceVersion getSupportedSourceVersion() { return javax.lang.model.SourceVersion.latestSupported(); }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            String name = annotation.getQualifiedName().toString();
            for (Element elem : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (name.endsWith(".Field"))                                                    processField(elem);
                else if (name.endsWith(".StaticFieldAlias") || name.endsWith(".StaticFieldAliasRW")) processStaticFieldAlias(elem);
                else if (name.endsWith(".Trampoline"))                                          processTrampoline(elem);
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

    private void processStaticFieldAlias(Element elem) {
        if (!(elem instanceof VariableElement field)) return;
        if (field.getModifiers().contains(Modifier.FINAL)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias/@Patch.StaticFieldAliasRW field must not be final", elem);
        }
        boolean hasPatch = field.getEnclosingElement().getAnnotationMirrors().stream()
            .anyMatch(m -> m.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch"));
        if (!hasPatch) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias/@Patch.StaticFieldAliasRW can only be used in a class annotated with @Patch", elem);
        }
    }

    private void processTrampoline(Element elem) {
        for (AnnotationMirror mirror : elem.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch.Trampoline")) continue;
            Map<? extends ExecutableElement, ? extends AnnotationValue> vals = mirror.getElementValues();
            boolean hasValue      = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("value"));
            boolean hasMethodName = vals.keySet().stream().anyMatch(k -> k.getSimpleName().contentEquals("methodName"));
            if (hasValue && hasMethodName) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Patch.Trampoline: specify either 'value' or 'methodName', not both", elem, mirror);
            }
        }
    }
}
