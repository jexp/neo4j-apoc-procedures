package apoc.processor;

import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserAggregationFunction;
import org.neo4j.procedure.UserFunction;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ApocProcessor extends AbstractProcessor {

    private List<String> procedureSignatures;

    private List<String> userFunctionSignatures;

    private SignatureVisitor signatureVisitor;

    private ExtensionClassWriter extensionClassWriter;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                Procedure.class.getName(),
                UserFunction.class.getName(),
                UserAggregationFunction.class.getName()
        );
    }


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        procedureSignatures = new ArrayList<>();
        userFunctionSignatures = new ArrayList<>();
        extensionClassWriter = new ExtensionClassWriter(processingEnv.getFiler());
        signatureVisitor = new SignatureVisitor(
                processingEnv.getElementUtils(),
                processingEnv.getMessager()
        );
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {

        annotations.forEach(annotation -> extractSignature(annotation, roundEnv));

        if (roundEnv.processingOver()) {
            extensionClassWriter.write(procedureSignatures, userFunctionSignatures);
        }
        return false;
    }

    private void extractSignature(TypeElement annotation, RoundEnvironment roundEnv) {
        List<String> signatures = accumulator(annotation);
        roundEnv.getElementsAnnotatedWith(annotation)
                .forEach(annotatedElement -> signatures.add(signatureVisitor.visit(annotatedElement)));
    }

    private List<String> accumulator(TypeElement annotation) {
        if (annotation.getQualifiedName().contentEquals(Procedure.class.getName())) {
            return procedureSignatures;
        }
        return userFunctionSignatures;
    }

}
