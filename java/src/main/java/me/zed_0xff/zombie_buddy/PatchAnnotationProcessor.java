package me.zed_0xff.zombie_buddy;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("me.zed_0xff.zombie_buddy.Patch.StaticFieldAlias")
public class PatchAnnotationProcessor extends AbstractProcessor {
    @Override
    public javax.lang.model.SourceVersion getSupportedSourceVersion() { return javax.lang.model.SourceVersion.latestSupported(); }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element elem : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (!(elem instanceof VariableElement field)) continue;
                if (field.getModifiers().contains(Modifier.FINAL)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias field must not be final", elem);
                }
                boolean hasPatch = field.getEnclosingElement().getAnnotationMirrors().stream()
                    .anyMatch(m -> m.getAnnotationType().asElement().toString().equals("me.zed_0xff.zombie_buddy.Patch"));
                if (!hasPatch) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Patch.StaticFieldAlias can only be used in a class annotated with @Patch", elem);
                }
            }
        }
        return true;
    }
}
