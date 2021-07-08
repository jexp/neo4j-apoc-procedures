package apoc.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class ExtensionClassWriter {

    private final Filer filer;

    public ExtensionClassWriter(Filer filer) {
        this.filer = filer;
    }

    public void write(List<String> procedureSignatures,
                      List<String> userFunctionSignatures) {

        try {
            JavaFile.builder("apoc", defineClass(procedureSignatures, userFunctionSignatures))
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TypeSpec defineClass(List<String> procedureSignatures, List<String> userFunctionSignatures) {
        return TypeSpec.classBuilder("ApocSignatures")
                .addModifiers(Modifier.PUBLIC)
                .addField(signatureListField("PROCEDURES", procedureSignatures))
                .addField(signatureListField("FUNCTIONS", userFunctionSignatures))
                .build();
    }

    private FieldSpec signatureListField(String fieldName, List<String> signatures) {
        ParameterizedTypeName fieldType = ParameterizedTypeName.get(
                ClassName.get(List.class),
                ClassName.get(String.class)
        );
        return FieldSpec.builder(fieldType, fieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(CodeBlock.builder()
                        .addStatement(String.format("List.of(%s)", placeholders(signatures)), signatures.toArray())
                        .build())
                .build();
    }

    private String placeholders(List<String> signatures) {
        // FIXME: find a way to manage the indentation automatically
        return signatures.stream().map((ignored) -> "$S").collect(Collectors.joining(",\n\t\t"));
    }
}
