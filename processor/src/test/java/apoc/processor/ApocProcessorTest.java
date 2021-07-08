package apoc.processor;

import com.google.testing.compile.CompilationRule;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.processing.Processor;

import static com.google.common.truth.Truth.assert_;
import static com.google.testing.compile.JavaSourceSubjectFactory.javaSource;

public class ApocProcessorTest {

    @Rule
    public CompilationRule compilationRule = new CompilationRule();

    Processor apocProcessor = new ApocProcessor();

    @Test
    public void generates_signatures() {
        assert_()
                .about(javaSource())
                .that(JavaFileObjects.forSourceLines("my.ApocProcedure",
                        "package my;\n" +
                                "\n" +
                                "import org.neo4j.procedure.Description;\n" +
                                "import org.neo4j.procedure.Name;\n" +
                                "import org.neo4j.procedure.Procedure;\n" +
                                "import org.neo4j.procedure.UserFunction;\n" +
                                "\n" +
                                "import java.util.List;\n" +
                                "import java.util.stream.Stream;\n" +
                                "\n" +
                                "" +
                                "class ApocProcedure {\n" +
                                "\n" +
                                "    @Procedure(name = \"apoc.nodes\")\n" +
                                "    @Description(\"apoc.nodes(node|id|[ids]) - quickly returns all nodes with these id's\")\n" +
                                "    public Stream<NodeResult> nodes(@Name(\"nodes\") Object ids) {\n" +
                                "        return Stream.empty();\n" +
                                "    }\n" +
                                "\n" +
                                "    " +
                                "@UserFunction\n" +
                                "    public String join(@Name(\"words\") List<String> words, @Name(\"separator\") String separator) {\n" +
                                "        return String.join(separator, words);\n" +
                                "    }\n" +
                                "    \n" +
                                "    @UserFunction(name = \"apoc.sum\")\n" +
                                "    public int sum(@Name(\"a\") int a, @Name(\"b\") int b) {\n" +
                                "        return a + b;\n" +
                                "    }\n" +
                                "}\n" +
                                "\n" +
                                "class NodeResult {\n" +
                                "    \n" +
                                "}"
                ))
                .processedWith(apocProcessor)
                .compilesWithoutError()
                .and()
                .generatesSources(JavaFileObjects.forSourceLines("apoc.ApocSignatures", "package apoc;\n" +
                        "\n" +
                        "import java.lang.String;\n" +
                        "import java.util.List;\n" +
                        "\n" +
                        "public class ApocSignatures {\n" +
                        "  public static final List<String> PROCEDURES = List.of(\"apoc.nodes\");\n" +
                        "  ;\n" +
                        "\n" +
                        "  public static final List<String> FUNCTIONS = List.of(\"my.join\",\n" +
                        "          \"apoc.sum\");\n" +
                        "  ;\n" +
                        "}"));
    }
}