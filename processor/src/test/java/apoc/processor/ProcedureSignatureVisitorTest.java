package apoc.processor;

import com.google.testing.compile.CompilationRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.procedure.Procedure;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.TypeElement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ProcedureSignatureVisitorTest {

    @Rule
    public CompilationRule compilationRule = new CompilationRule();

    ElementVisitor<String, Void> visitor;

    TypeElement typeElement;

    @Before
    public void prepare() {
        visitor = new SignatureVisitor(compilationRule.getElements(), mock(Messager.class));
        typeElement = compilationRule.getElements().getTypeElement(ProcedureSignatureVisitorTest.class.getName());
    }

    @Test
    public void gets_the_annotated_name_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myProcedure");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.proc");
    }

    @Test
    public void gets_the_annotated_value_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myProcedure2");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.proc2");
    }

    @Test
    public void gets_the_annotated_name_over_value() {
        Element method = findMemberByName(typeElement, "myProcedure3");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.proc3");
    }

    @Test
    public void gets_the_default_name_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myDefaultNamedProcedure");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("apoc.processor.myDefaultNamedProcedure");
    }

    @Procedure(name = "my.proc")
    public static void myProcedure() {

    }

    @Procedure(value = "my.proc2")
    public static void myProcedure2() {

    }

    @Procedure(name = "my.proc3", value = "ignored")
    public static void myProcedure3() {

    }

    @Procedure
    public static void myDefaultNamedProcedure() {

    }

    private Element findMemberByName(TypeElement typeElement, String name) {
        return compilationRule.getElements().getAllMembers(typeElement).stream().filter(e -> e.getSimpleName().contentEquals(name)).findFirst().get();
    }
}