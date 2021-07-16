package apoc.custom;

import apoc.util.JsonUtil;
import org.antlr.v4.runtime.*;
import org.neo4j.internal.kernel.api.procs.*;
import org.neo4j.procedure.Mode;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.*;

public class Signatures {

    public static final String SIGNATURE_SYNTAX_ERROR = "Syntax error(s) in signature definition %s. " +
            "\nNote that procedure/function name, input and output names must have at least 2 character:\n";
    private final String prefix;

    public Signatures(String prefix) {
        this.prefix = prefix;
    }

    public Signatures() {
        this(null);
    }

    public SignatureParser.ProcedureContext parseProcedure(String procedureSignatureText) {
        List<String> errors = new ArrayList<>();
        SignatureParser signatureParser = parse(procedureSignatureText, errors);
        final SignatureParser.ProcedureContext signatureParsed = signatureParser.procedure();
        checkSignatureSyntax(procedureSignatureText, errors);
        return signatureParsed;
    }

    public SignatureParser.FunctionContext parseFunction(String functionSignatureText) {
        List<String> errors = new ArrayList<>();
        SignatureParser signatureParser = parse(functionSignatureText, errors);
        final SignatureParser.FunctionContext signatureParsed = signatureParser.function();
        checkSignatureSyntax(functionSignatureText, errors);
        return signatureParsed;
    }

    private void checkSignatureSyntax(String functionSignatureText, List<String> errors) {
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.format(SIGNATURE_SYNTAX_ERROR, functionSignatureText) 
                    + String.join("\n", errors));
        }
    }

    private SignatureParser parse(String signatureText, List<String> errors) {
        CodePointCharStream inputStream = CharStreams.fromString(signatureText);
        SignatureLexer signatureLexer = new SignatureLexer(inputStream);
        CommonTokenStream commonTokenStream = new CommonTokenStream(signatureLexer);
        SignatureParser signatureParser = new SignatureParser(commonTokenStream);
        signatureParser.addErrorListener(new BaseErrorListener() {
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                errors.add("line " + line + ":" + charPositionInLine + " " + msg);
            }
        });
        return signatureParser;
    }

    public ProcedureSignature toProcedureSignature(SignatureParser.ProcedureContext signature) {
        return toProcedureSignature(signature, null, Mode.DEFAULT);
    }

    public ProcedureSignature toProcedureSignature(SignatureParser.ProcedureContext signature, String description, Mode mode) {
        QualifiedName name = new QualifiedName(namespace(signature.namespace()), name(signature.name()));
        List<FieldSignature> outputSignature =
                signature.results().empty() != null ? ProcedureSignature.VOID :
                        signature.results().result().stream().map(p ->
                                FieldSignature.outputField(name(p.name()), type(p.type()))).collect(Collectors.toList());
        // todo deprecated + default value
        List<FieldSignature> inputSignatures = signature.parameter().stream().map(p -> FieldSignature.inputField(name(p.name()), type(p.type()), defaultValue(p.defaultValue(), type(p.type())))).collect(Collectors.toList());
        boolean admin = false;
        String deprecated = "";
        String[] allowed = new String[0];
        String warning = null; // "todo warning";
        boolean eager = false;
        boolean caseInsensitive = true;
        return createProcedureSignature(name, inputSignatures, outputSignature, mode, admin, deprecated, allowed, description, warning, eager, caseInsensitive, false, false);
    }

    public List<String> namespace(SignatureParser.NamespaceContext namespaceContext) {
        List<String> parsed = namespaceContext == null ? Collections.emptyList() : namespaceContext.name().stream().map(this::name).collect(Collectors.toList());
        if (prefix == null) {
            return parsed;
        } else {
            ArrayList<String> namespace = new ArrayList<>();
            namespace.add(prefix);
            namespace.addAll(parsed);
            return namespace;
        }
    }

    public UserFunctionSignature toFunctionSignature(SignatureParser.FunctionContext signature, String description) {
        QualifiedName name = new QualifiedName(namespace(signature.namespace()), name(signature.name()));

        Neo4jTypes.AnyType type = type(signature.type());

        List<FieldSignature> inputSignatures = signature.parameter().stream().map(p -> FieldSignature.inputField(name(p.name()), type(p.type()), defaultValue(p.defaultValue(), type(p.type())))).collect(Collectors.toList());

        String deprecated = "";
        String[] allowed = new String[0];
        boolean caseInsensitive = true;
        return new UserFunctionSignature(name, inputSignatures, type, deprecated, allowed, description, caseInsensitive);
    }

    private DefaultParameterValue defaultValue(SignatureParser.DefaultValueContext defaultValue, Neo4jTypes.AnyType type) {
        if (defaultValue == null) return DefaultParameterValue.nullValue(type);
        SignatureParser.ValueContext v = defaultValue.value();
        if (v.nullValue() != null)
            return DefaultParameterValue.nullValue(type);
        if (v.boolValue() != null)
            return DefaultParameterValue.ntBoolean(Boolean.parseBoolean(v.boolValue().getText()));
        if (v.stringValue() != null)
            return DefaultParameterValue.ntString(v.stringValue().getText());
        if (v.INT_VALUE() != null)
            return DefaultParameterValue.ntInteger(Integer.parseInt(v.INT_VALUE().getText()));
        if (v.FLOAT_VALUE() != null)
            return DefaultParameterValue.ntFloat(Integer.parseInt(v.FLOAT_VALUE().getText()));
        if (v.mapValue() != null) {
            Map map = JsonUtil.parse(v.mapValue().getText(), null, Map.class);
            return DefaultParameterValue.ntMap(map);
        }
        if (v.listValue() != null) {
            List<?> list = JsonUtil.parse(v.listValue().getText(), null, List.class);
            return DefaultParameterValue.ntList(list, ((Neo4jTypes.ListType) type).innerType());
        }
        return DefaultParameterValue.nullValue(type);
    }

    public String name(SignatureParser.NameContext ns) {
        if (ns.IDENTIFIER() != null) return ns.IDENTIFIER().getText();
        if (ns.QUOTED_IDENTIFIER() != null) return ns.QUOTED_IDENTIFIER().getText(); // todo
        throw new IllegalStateException("Invalid Name " + ns);
    }

    private Neo4jTypes.AnyType type(SignatureParser.TypeContext typeContext) {
        if (typeContext.list_type() != null) {
            return Neo4jTypes.NTList(type(typeContext.list_type().opt_type()));
        }
        if (typeContext.opt_type() != null) {
            return type(typeContext.opt_type());
        }
        return Neo4jTypes.NTAny;
    }

    private Neo4jTypes.AnyType type(SignatureParser.Opt_typeContext opt_type) {
        switch (opt_type.base_type().getText()) {
            case "ANY":
                return NTAny;
            case "MAP":
                return NTMap;
            case "NODE":
                return NTNode;
            case "REL":
                return NTRelationship;
            case "RELATIONSHIP":
                return NTRelationship;
            case "EDGE":
                return NTRelationship;
            case "PATH":
                return NTPath;
            case "NUMBER":
                return NTNumber;
            case "LONG":
                return NTInteger;
            case "INT":
                return NTInteger;
            case "INTEGER":
                return NTInteger;
            case "FLOAT":
                return NTFloat;
            case "DOUBLE":
                return NTFloat;
            case "BOOL":
                return NTBoolean;
            case "BOOLEAN":
                return NTBoolean;
            case "DATE":
                return NTDate;
            case "TIME":
                return NTTime;
            case "LOCALTIME":
                return NTLocalTime;
            case "DATETIME":
                return NTDateTime;
            case "LOCALDATETIME":
                return NTLocalDateTime;
            case "DURATION":
                return NTDuration;
            case "POINT":
                return NTPoint;
            case "GEO":
                return NTGeometry;
            case "GEOMETRY":
                return NTGeometry;
            case "STRING":
                return NTString;
            case "TEXT":
                return NTString;
            default:
                return NTString;
        }
    }

    public UserFunctionSignature asFunctionSignature(String signature, String description) {
        SignatureParser.FunctionContext functionContext = parseFunction(signature);
        return toFunctionSignature(functionContext, description);
    }

    public ProcedureSignature asProcedureSignature(String signature, String description, Mode mode) {
        SignatureParser.ProcedureContext ctx = parseProcedure(signature);
        return toProcedureSignature(ctx, description, mode);
    }

    public static ProcedureSignature createProcedureSignature(QualifiedName name,
                                                              List<FieldSignature> inputSignature,
                                                              List<FieldSignature> outputSignature,
                                                              Mode mode,
                                                              boolean admin,
                                                              String deprecated,
                                                              String[] allowed,
                                                              String description,
                                                              String warning,
                                                              boolean eager,
                                                              boolean caseInsensitive,
                                                              boolean systemProcedure,
                                                              boolean internal) {
        try {
            // in Neo4j 4.0.5 org.neo4j.internal.kernel.api.procs.ProcedureSignature
            // changed the signature adding a boolean at the end and without leaving the old signature
            // in order to maintain the backwards compatibility with version prior to 4.0.5 we use the
            // reflection to create a new instance of the class
            final Class<?> clazz = Class.forName("org.neo4j.internal.kernel.api.procs.ProcedureSignature");
            final Constructor<?>[] constructors = clazz.getConstructors();
            for (int i = 0; i < constructors.length; i++) {
                final Constructor<?> constructor = constructors[i];
                switch (constructor.getParameterCount()) {
                    case 13:
                        return (ProcedureSignature) constructor.newInstance(name,
                                inputSignature,
                                outputSignature,
                                mode,
                                admin,
                                deprecated,
                                allowed,
                                description,
                                warning,
                                eager,
                                caseInsensitive,
                                systemProcedure,
                                internal);
                    case 12:
                        return (ProcedureSignature) constructor.newInstance(name,
                                inputSignature,
                                outputSignature,
                                mode,
                                admin,
                                deprecated,
                                allowed,
                                description,
                                warning,
                                eager,
                                caseInsensitive,
                                systemProcedure);
                }
            }
            throw new RuntimeException("Constructor of org.neo4j.internal.kernel.api.procs.ProcedureSignature not found");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
