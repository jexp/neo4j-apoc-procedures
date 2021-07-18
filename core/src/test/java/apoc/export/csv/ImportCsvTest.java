package apoc.export.csv;

import apoc.ApocSettings;
import apoc.util.TestUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.util.MapUtil.map;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class ImportCsvTest {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(ApocSettings.apoc_import_file_enabled, true)
            .withSetting(ApocSettings.apoc_export_file_enabled, true)
            .withSetting(GraphDatabaseSettings.allow_file_urls, true)
            .withSetting(GraphDatabaseSettings.load_csv_file_url_root, new File("src/test/resources/csv-inputs").toPath().toAbsolutePath());

    final Map<String, String> testCsvs = Collections
            .unmodifiableMap(Stream.of(
                    new AbstractMap.SimpleEntry<>("array", ":ID|name:STRING[]\n" +
                            "1|John;Bob;Alice\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-basic-affiliated-with", ":START_ID,:END_ID\n" +
                            "1,3\n" +
                            "2,4\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-basic-companies", "companyId:ID,name:STRING\n" +
                            "4,Neo4j\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-basic-persons", "personId:ID,name:STRING\n" +
                            "1,John\n" +
                            "2,Jane\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-basic-unis", "uniId:ID,name:STRING\n" +
                            "3,TU Munich\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-idspaces-affiliated-with", ":START_ID(Person),:END_ID(Organisation)\n" +
                            "1,1\n" +
                            "2,2\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-idspaces-companies", "companyId:ID(Organisation),name:STRING\n" +
                            "2,Neo4j\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-idspaces-persons", "personId:ID(Person),name:STRING\n" +
                            "1,John\n" +
                            "2,Jane\n"),
                    new AbstractMap.SimpleEntry<>("custom-ids-idspaces-unis", "uniId:ID(Organisation),name:STRING\n" +
                            "1,TU Munich\n"),
                    new AbstractMap.SimpleEntry<>("id-idspaces", ":ID(Person)|name:STRING\n" +
                            "1|John\n" +
                            "2|Jane\n"),
                    new AbstractMap.SimpleEntry<>("id-idspaces-with-dash", ":ID(Person-Id)|name:STRING\n" +
                            "1|John\n" +
                            "2|Jane\n"),
                    new AbstractMap.SimpleEntry<>("id", "id:ID|name:STRING\n" +
                            "1|John\n" +
                            "2|Jane\n"),
                    new AbstractMap.SimpleEntry<>("id-with-duplicates", "id:ID|name:STRING\n" +
                            "1|John\n" +
                            "1|Jane\n"),
                    new AbstractMap.SimpleEntry<>("ignore-nodes", ":ID|firstname:STRING|lastname:IGNORE|age:INT\n" +
                            "1|John|Doe|25\n" +
                            "2|Jane|Doe|26\n"),
                    new AbstractMap.SimpleEntry<>("ignore-relationships", ":START_ID|:END_ID|prop1:IGNORE|prop2:INT\n" +
                            "1|2|a|3\n" +
                            "2|1|b|6\n"),
                    new AbstractMap.SimpleEntry<>("label", ":ID|:LABEL|name:STRING\n" +
                            "1|Student;Employee|John\n"),
                    new AbstractMap.SimpleEntry<>("knows", ":START_ID,:END_ID,since:INT\n" +
                            "1,2,2016\n" +
                            "10,11,2014\n" +
                            "11,12,2013"),
                    new AbstractMap.SimpleEntry<>("persons", ":ID,name:STRING,speaks:STRING[]\n" +
                            "1,John,\"en,fr\"\n" +
                            "2,Jane,\"en,de\""),
                    new AbstractMap.SimpleEntry<>("quoted", "id:ID|:LABEL|name:STRING\n" +
                            "'1'|'Student:Employee'|'John'\n"),
                    new AbstractMap.SimpleEntry<>("rel-on-ids-idspaces", ":START_ID(Person)|:END_ID(Person)|since:INT\n" +
                            "1|2|2016\n"),
                    new AbstractMap.SimpleEntry<>("rel-on-ids", "x:START_ID|:END_ID|since:INT\n" +
                            "1|2|2016\n"),
                    new AbstractMap.SimpleEntry<>("rel-type", ":START_ID|:END_ID|:TYPE|since:INT\n" +
                            "1|2|FRIENDS_WITH|2016\n" +
                            "2|1||2016\n"),
                    new AbstractMap.SimpleEntry<>("typeless", ":ID|name\n" +
                            "1|John\n" +
                            "2|Jane\n"),
                    new AbstractMap.SimpleEntry<>("personsWithoutIdField", "name:STRING\n" +
                            "John\n" +
                            "Jane\n"),
                    new AbstractMap.SimpleEntry<>("emptyInteger", 
                            ":ID(node_space_1),:LABEL,str_attribute:STRING,int_attribute:INT,int_attribute_array:INT[],double_attribute_array:FLOAT[]\n" +
                            "n1,Thing,once upon a time,1,\"2;3\",\"2.3;3.5\"\n" +
                            "n2,Thing,,2,\"4;5\",\"2.6;3.6\"\n" +
                            "n3,Thing,,,,\n"
                    )
            ).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)));

    @Before
    public void setUp() throws IOException {
        for (Map.Entry<String, String> entry : testCsvs.entrySet()) {
            CsvTestUtil.saveCsvFile(entry.getKey(), entry.getValue());
        }

        TestUtil.registerProcedure(db, ImportCsv.class);
    }

    @Test
    public void testNodesWithIds() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/id.csv",
                        "config", map("delimiter", '|', "stringIds", false)
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name");
        assertThat(names, Matchers.containsInAnyOrder("Jane", "John"));

        List<Long> ids = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.id AS id ORDER BY id");
        assertThat(ids, Matchers.contains(1L, 2L));
    }

    @Test
    public void testCallAsString() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv(" +
                        "[{fileName: 'file:/quoted.csv', labels: ['Person']}], " +
                        "[], " +
                        "{delimiter: '|', arrayDelimiter: ':', quotationCharacter: '\\'', stringIds: false})",
                map(),
                (r) -> {
                    assertEquals(1L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        Assert.assertEquals("John", TestUtil.<String>singleResultFirstColumn(db, "MATCH (n:Person:Student:Employee) RETURN n.name AS name ORDER BY name"));

        long id = TestUtil.<Long>singleResultFirstColumn(db, "MATCH (n:Person:Student:Employee) RETURN n.id AS id ORDER BY id");
        Assert.assertEquals(1L, id);
    }

    @Test
    public void testNodesWithIdSpaces() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/id-idspaces.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name");
        assertThat(names, Matchers.containsInAnyOrder("Jane", "John"));
    }

    @Test
    public void testNodesWithIdSpacesWithDash() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/id-idspaces-with-dash.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name");
        assertThat(names, Matchers.containsInAnyOrder("Jane", "John"));
    }

    @Test
    public void testCustomLabels() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/label.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(1L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );
        List<String> names = TestUtil.firstColumn(db, "MATCH (n) UNWIND labels(n) AS label RETURN label ORDER BY label");
        assertThat(names, Matchers.contains("Employee", "Person", "Student"));
    }

    @Test
    public void testArray() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/array.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(1L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) UNWIND n.name AS name RETURN name ORDER BY name");
        assertThat(names, Matchers.contains("Alice", "Bob", "John"));
    }

    @Test
    public void testDefaultTypedField() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/typeless.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name");
        assertThat(names, Matchers.containsInAnyOrder("Jane", "John"));
    }

    @Test
    public void testCsvWithoutIdField() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/personsWithoutIdField.csv",
                        "config", map()
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        List<String> names = TestUtil.firstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name");
        assertEquals(List.of("Jane", "John"), names);
    }

    @Test
    public void testCustomRelationshipTypes() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $nodeFile, labels: ['Person']}], [{fileName: $relFile, type: 'KNOWS'}], $config)",
                map(
                        "nodeFile", "file:/id.csv",
                        "relFile", "file:/rel-type.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(2L, r.get("relationships"));
                }
        );

        Assert.assertEquals("John Jane", TestUtil.singleResultFirstColumn(db, "MATCH (p1:Person)-[:FRIENDS_WITH]->(p2:Person) RETURN p1.name + ' ' + p2.name AS pair ORDER BY pair"));
        Assert.assertEquals("Jane John", TestUtil.singleResultFirstColumn(db, "MATCH (p1:Person)-[:KNOWS]->(p2:Person) RETURN p1.name + ' ' + p2.name AS pair ORDER BY pair"));
    }
    
    @Test
    public void testEmptyInteger() {
        TestUtil.testCall(db, "CALL apoc.import.csv([{fileName: 'file:/emptyInteger.csv', labels: ['entity']}], [], {ignoreEmptyString: true})",
                r -> assertEquals(3L, r.get("nodes")));

        TestUtil.testResult(db, "MATCH (node:Thing) RETURN node ORDER BY node.int_attribute", r -> {
                    final Node firstNode = (Node) r.next().get("node");
                    final Map<String, Object> firstProps = firstNode.getAllProperties();
                    assertEquals(1L, firstProps.get("int_attribute"));
                    assertArrayEquals(new long[] { 2L, 3L }, (long[]) firstProps.get("int_attribute_array"));
                    assertArrayEquals(new double[] { 2.3D, 3.5D }, (double[]) firstProps.get("double_attribute_array"), 0);
                    final Node secondNode = (Node) r.next().get("node");
                    final Map<String, Object> secondProps = secondNode.getAllProperties();
                    assertEquals(2L, secondProps.get("int_attribute"));
                    assertArrayEquals(new long[] { 4L, 5L }, (long[]) secondProps.get("int_attribute_array"));
                    assertArrayEquals(new double[] { 2.6D, 3.6D }, (double[]) secondProps.get("double_attribute_array"), 0);
                    final Node thirdNode = (Node) r.next().get("node");
                    final Map<String, Object> thirdProps = thirdNode.getAllProperties();
                    assertNull(thirdProps.get("int_attribute"));
                    assertNull(thirdProps.get("int_attribute_array"));
                    assertNull(thirdProps.get("double_attribute_array"));
                    assertNull(thirdProps.get("str_attribute"));
                    assertFalse(r.hasNext());
                });
    }

    @Test
    public void testRelationshipWithoutIdSpaces() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $nodeFile, labels: ['Person']}], [{fileName: $relFile, type: 'KNOWS'}], $config)",
                map(
                        "nodeFile", "file:/id.csv",
                        "relFile", "file:/rel-on-ids.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                }
        );
        Assert.assertEquals("John Jane", TestUtil.singleResultFirstColumn(db, "MATCH (p1:Person)-[:KNOWS]->(p2:Person) RETURN p1.name + ' ' + p2.name AS pair ORDER BY pair"));
    }

    @Test
    public void testRelationshipWithIdSpaces() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $nodeFile, labels: ['Person']}], [{fileName: $relFile, type: 'KNOWS'}], $config)",
                map(
                        "nodeFile", "file:/id-idspaces.csv",
                        "relFile", "file:/rel-on-ids-idspaces.csv",
                        "config", map("delimiter", '|')
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(1L, r.get("relationships"));
                }
        );
        Assert.assertEquals("John Jane", TestUtil.singleResultFirstColumn(db, "MATCH (p1:Person)-[:KNOWS]->(p2:Person) RETURN p1.name + ' ' + p2.name AS pair ORDER BY pair"));
    }

    @Test
    public void testRelationshipWithCustomIdNames() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv(" +
                        "[" +
                        "  {fileName: $personFile, labels: ['Person']}," +
                        "  {fileName: $companyFile, labels: ['Company']}," +
                        "  {fileName: $universityFile, labels: ['University']}" +
                        "]," +
                        "[" +
                        "  {fileName: $relFile, type: 'AFFILIATED_WITH'}" +
                        "]," +
                        " $config)",
                map(
                        "personFile", "file:/custom-ids-basic-persons.csv",
                        "companyFile", "file:/custom-ids-basic-companies.csv",
                        "universityFile", "file:/custom-ids-basic-unis.csv",
                        "relFile", "file:/custom-ids-basic-affiliated-with.csv",
                        "config", map()
                ),
                (r) -> {
                    assertEquals(4L, r.get("nodes"));
                    assertEquals(2L, r.get("relationships"));
                }
        );

        List<String> pairs = TestUtil.firstColumn(db, "MATCH (p:Person)-[:AFFILIATED_WITH]->(org) RETURN p.name + ' ' + org.name AS pair ORDER BY pair");
        assertThat(pairs, Matchers.contains("Jane Neo4j", "John TU Munich"));
    }

    @Test
    public void testRelationshipWithCustomIdNamesAndIdSpaces() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv(" +
                        "[" +
                        "  {fileName: $personFile, labels: ['Person']}," +
                        "  {fileName: $companyFile, labels: ['Company']}," +
                        "  {fileName: $universityFile, labels: ['University']}" +
                        "]," +
                        "[" +
                        "  {fileName: $relFile, type: 'AFFILIATED_WITH'}" +
                        "]," +
                        " $config)",
                map(
                        "personFile", "file:/custom-ids-idspaces-persons.csv",
                        "companyFile", "file:/custom-ids-idspaces-companies.csv",
                        "universityFile", "file:/custom-ids-idspaces-unis.csv",
                        "relFile", "file:/custom-ids-idspaces-affiliated-with.csv",
                        "config", map()
                ),
                (r) -> {
                    assertEquals(4L, r.get("nodes"));
                    assertEquals(2L, r.get("relationships"));
                }
        );

        List<String> pairs = TestUtil.firstColumn(db, "MATCH (p:Person)-[:AFFILIATED_WITH]->(org) RETURN p.name + ' ' + org.name AS pair ORDER BY pair");
        assertThat(pairs, Matchers.contains("Jane Neo4j", "John TU Munich"));
    }

    @Test
    public void ignoreFieldType() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $nodeFile, labels: ['Person']}], [{fileName: $relFile, type: 'KNOWS'}], $config)",
                map(
                        "nodeFile", "file:/ignore-nodes.csv",
                        "relFile", "file:/ignore-relationships.csv",
                        "config", map("delimiter", '|', "batchSize", 1)
                ),
                (r) -> {
                    assertEquals(2L, r.get("nodes"));
                    assertEquals(2L, r.get("relationships"));
                }
        );

        List<Long> ages = TestUtil.firstColumn(db, "MATCH (p:Person) RETURN p.age AS age ORDER BY age");
        assertThat(ages, Matchers.contains(25L, 26L));

        List<String> pairs = TestUtil.firstColumn(db, "MATCH (p1:Person)-[k:KNOWS]->(p2:Person)\n" +
                "WHERE p1.lastname IS NULL\n" +
                "  AND p2.lastname IS NULL\n" +
                "  AND k.prop1 IS NULL\n" +
                "RETURN p1.firstname + ' ' + p1.age + ' <' + k.prop2 + '> ' + p2.firstname + ' ' + p2.age AS pair ORDER BY pair"
        );
        assertThat(pairs, Matchers.contains("Jane 26 <6> John 25", "John 25 <3> Jane 26"));
    }

    @Test(expected = QueryExecutionException.class)
    public void testNoDuplicateEndpointsCreated() {
        // some of the endpoints of the edges in 'knows.csv' do not exist,
        // hence this should throw an exception
        db.executeTransactionally("CALL apoc.import.csv([{fileName: $nodeFile, labels: ['Person']}], [{fileName: $relFile, type: 'KNOWS'}], $config)",
                map("nodeFile", "file:/persons.csv",
                    "relFile", "file:/knows.csv",
                    "config", map("stringIds", false)));
    }

    @Test(expected = QueryExecutionException.class)
    public void testIgnoreDuplicateNodes() {
        db.executeTransactionally(
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/id-with-duplicates.csv",
                        "config", map("delimiter", '|', "stringIds", false, "ignoreDuplicateNodes", false)
                ));
    }

    @Test
    public void testLoadDuplicateNodes() {
        TestUtil.testCall(
                db,
                "CALL apoc.import.csv([{fileName: $file, labels: ['Person']}], [], $config)",
                map(
                        "file", "file:/id-with-duplicates.csv",
                        "config", map("delimiter", '|', "stringIds", false, "ignoreDuplicateNodes", true)
                ),
                (r) -> {
                    assertEquals(1L, r.get("nodes"));
                    assertEquals(0L, r.get("relationships"));
                }
        );

        Assert.assertEquals("John", TestUtil.singleResultFirstColumn(db, "MATCH (n:Person) RETURN n.name AS name ORDER BY name"));

        long id = TestUtil.<Long>singleResultFirstColumn(db, "MATCH (n:Person) RETURN n.id AS id ORDER BY id");
        Assert.assertEquals(1L, id);
    }

}
