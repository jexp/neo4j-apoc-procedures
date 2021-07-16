package apoc.custom;

import apoc.util.Util;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.junit.Test;
import org.neo4j.internal.kernel.api.procs.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.*;

public class SignatureTest {

    private final Signatures sigs = new Signatures();

    @Test
    public void parseFunctionSignature() {
        String procedureSignatureText = "db.index.fulltext.queryRelationships(indexName :: STRING?, queryString :: STRING?) :: FLOAT?";
        SignatureParser.FunctionContext signature = sigs.parseFunction(procedureSignatureText);
        UserFunctionSignature ps = sigs.toFunctionSignature(signature, null);
        // todo , inputSignature, outputSignature, mode, admin, deprecated, allowed, description, warning, eager, caseInsensitive)
        System.out.println("ps = " + ps);
        assertEquals("queryRelationships", ps.name().name());
        assertEquals("indexName,queryString", ps.inputSignature().stream().map(FieldSignature::name).collect(Collectors.joining(",")));
        assertEquals(NTFloat, ps.outputType());
    }

    @Test
    public void parseSimpleSignature() {
        String procedureSignatureText = "db.index.fulltext.queryRelationships(indexName :: STRING?, queryString :: STRING?) :: (relationship :: RELATIONSHIP?, score :: FLOAT?)";
        SignatureParser.ProcedureContext signature = sigs.parseProcedure(procedureSignatureText);
        ProcedureSignature ps = sigs.toProcedureSignature(signature);
        // todo , inputSignature, outputSignature, mode, admin, deprecated, allowed, description, warning, eager, caseInsensitive)
        System.out.println("ps = " + ps);
        assertEquals("queryRelationships", ps.name().name());
        assertEquals("indexName,queryString", ps.inputSignature().stream().map(FieldSignature::name).collect(Collectors.joining(",")));
        assertEquals("relationship,score", ps.outputSignature().stream().map(FieldSignature::name).collect(Collectors.joining(",")));
        assertEquals(asList(NTRelationship, NTFloat), ps.outputSignature().stream().map(FieldSignature::neo4jType).collect(Collectors.toList()));
    }

    @Test
    public void parseWithDefaultValueAndVoidReturn() {
        String procedureSignatureText = "db.awaitIndex(index :: STRING?, timeOutSeconds = 300 :: INTEGER?) :: VOID";
        SignatureParser.ProcedureContext signature = sigs.parseProcedure(procedureSignatureText);
        ProcedureSignature ps = sigs.toProcedureSignature(signature);
        assertEquals("awaitIndex", ps.name().name());
        assertEquals("index,timeOutSeconds", ps.inputSignature().stream().map(FieldSignature::name).collect(Collectors.joining(",")));
        assertEquals(asList(null, 300L), ps.inputSignature().stream().map(FieldSignature::defaultValue).map(ov -> ov.map(DefaultParameterValue::value).orElse(null)).collect(Collectors.toList()));
        assertEquals(asList(NTString, NTInteger), ps.inputSignature().stream().map(FieldSignature::neo4jType).collect(Collectors.toList()));
        assertTrue(ps.outputSignature().isEmpty());
    }

    @Test
    public void parseWithMapDefaultValue() {
        String procedureSignatureText = "db.awaitIndex(index :: STRING?, timeOutSeconds = {} :: MAP?) :: VOID";
        SignatureParser.ProcedureContext signature = sigs.parseProcedure(procedureSignatureText);
        ProcedureSignature ps = sigs.toProcedureSignature(signature);
        assertEquals("awaitIndex", ps.name().name());
        assertEquals("index,timeOutSeconds", ps.inputSignature().stream().map(FieldSignature::name).collect(Collectors.joining(",")));
        assertEquals(asList(null, emptyMap()), ps.inputSignature().stream().map(FieldSignature::defaultValue).map(ov -> ov.map(DefaultParameterValue::value).orElse(null)).collect(Collectors.toList()));
        assertEquals(asList(NTString, NTMap), ps.inputSignature().stream().map(FieldSignature::neo4jType).collect(Collectors.toList()));
        assertTrue(ps.outputSignature().isEmpty());
    }

    @Test
    public void parseManySignatures() throws IOException {
        InputStream signatures = Util.class.getClassLoader().getResourceAsStream("signatures.csv");
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(signatures)).withSkipLines(1).build()) {
            for (String[] line : reader) {
                try {
                    SignatureParser.ProcedureContext signature = sigs.parseProcedure(line[1]);
                    ProcedureSignature ps = sigs.toProcedureSignature(signature);
                    assertEquals(line[0], ps.name().toString());
                } catch (RuntimeException e) {
                    System.out.println("failed parsing " + line[0] + " -> " + line[1]);
                }
            }
        }
    }
}
