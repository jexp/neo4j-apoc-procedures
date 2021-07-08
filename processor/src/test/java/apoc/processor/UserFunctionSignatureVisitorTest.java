package apoc.processor;

import com.google.testing.compile.CompilationRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.procedure.UserFunction;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.TypeElement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class UserFunctionSignatureVisitorTest {

    @Rule
    public CompilationRule compilationRule = new CompilationRule();

    ElementVisitor<String, Void> visitor;

    TypeElement typeElement;

    @Before
    public void prepare() {
        visitor = new SignatureVisitor(compilationRule.getElements(), mock(Messager.class));
        typeElement = compilationRule.getElements().getTypeElement(UserFunctionSignatureVisitorTest.class.getName());
    }

    @Test
    public void gets_the_annotated_name_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myFunction");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.func");
    }

    @Test
    public void gets_the_annotated_value_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myFunction2");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.func2");
    }

    @Test
    public void gets_the_annotated_name_over_value() {
        Element method = findMemberByName(typeElement, "myFunction3");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("my.func3");
    }

    @Test
    public void gets_the_default_name_of_the_procedure() {
        Element method = findMemberByName(typeElement, "myDefaultNamedFunction");

        String result = visitor.visit(method);

        assertThat(result).isEqualTo("apoc.processor.myDefaultNamedFunction");
    }

    @UserFunction(name = "my.func")
    public static void myFunction() {

    }

    @UserFunction(value = "my.func2")
    public static void myFunction2() {

    }

    @UserFunction(name = "my.func3", value = "ignored")
    public static void myFunction3() {

    }

    @UserFunction
    public static void myDefaultNamedFunction() {

    }

    private Element findMemberByName(TypeElement typeElement, String name) {
        return compilationRule.getElements().getAllMembers(typeElement).stream().filter(e -> e.getSimpleName().contentEquals(name)).findFirst().get();
    }
}