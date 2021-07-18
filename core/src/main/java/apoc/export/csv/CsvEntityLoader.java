package apoc.export.csv;

import apoc.export.util.BatchTransaction;
import apoc.export.util.CountingReader;
import apoc.export.util.ProgressReporter;
import apoc.load.CSVResult;
import apoc.load.Mapping;
import apoc.load.util.Results;
import apoc.util.FileUtils;
import com.opencsv.CSVReader;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CsvEntityLoader {

    private final CsvLoaderConfig clc;
    private final ProgressReporter reporter;
    private final Log log;

    /**
     * @param clc configuration object
     * @param reporter
     */
    public CsvEntityLoader(CsvLoaderConfig clc, ProgressReporter reporter, Log log) {
        this.clc = clc;
        this.reporter = reporter;
        this.log = log;
    }

    /**
     * Loads nodes from a CSV file with given labels to an online database, and fills the {@code idMapping},
     * which will be used by the {@link #loadRelationships(String, String, GraphDatabaseService, Map)}
     * method.
     *
     * @param fileName URI of the CSV file representing the node
     * @param labels list of node labels to be applied to each node
     * @param db running database instance
     * @param idMapping to be filled with the mapping between the CSV ids and the DB's internal node ids
     * @throws IOException
     */
    public void loadNodes(final String fileName, final List<String> labels, final GraphDatabaseService db,
                          final Map<String, Map<String, Long>> idMapping) throws IOException {
        final CountingReader reader = FileUtils.readerFor(fileName);
        final String header = readFirstLine(reader);
        reader.skip(clc.getSkipLines() - 1);
        final List<CsvHeaderField> fields = CsvHeaderFields.processHeader(header, clc.getDelimiter(), clc.getQuotationCharacter());

        final Optional<CsvHeaderField> idField = fields.stream()
                .filter(f -> CsvLoaderConstants.ID_FIELD.equals(f.getType()))
                .findFirst();

        if (!idField.isPresent()) {
            log.warn("Please note that if no ID is specified, the node will be imported but it will not be able to be connected by any relationships during the import");
        }

        final Optional<String> idAttribute = idField.isPresent() ? Optional.of(idField.get().getName()) : Optional.empty();
        final String idSpace = idField.isPresent() ? idField.get().getIdSpace() : CsvLoaderConstants.DEFAULT_IDSPACE;

        idMapping.putIfAbsent(idSpace, new HashMap<>());
        final Map<String, Long> idspaceIdMapping = idMapping.get(idSpace);

        final Map<String, Mapping> mapping = fields.stream().collect(
                Collectors.toMap(
                        CsvHeaderField::getName,
                        f -> {
                            final Map<String, Object> mappingMap = Collections
                                    .unmodifiableMap(Stream.of(
                                            new AbstractMap.SimpleEntry<>("type", f.getType()),
                                            new AbstractMap.SimpleEntry<>("array", f.isArray())
                                    ).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)));
                            return new Mapping(f.getName(), mappingMap, clc.getArrayDelimiter(), false);
                        }
                )
        );

        final CSVReader csv = new CSVReader(reader, clc.getDelimiter(), clc.getQuotationCharacter());

        final String[] loadCsvCompatibleHeader = fields.stream().map(f -> f.getName()).toArray(String[]::new);
        int lineNo = 0;
        try (BatchTransaction btx = new BatchTransaction(db, clc.getBatchSize(), reporter)) {
            for (String[] line : csv.readAll()) {
                lineNo++;

                final EnumSet<Results> results = EnumSet.of(Results.map);
                final CSVResult result = new CSVResult(
                        loadCsvCompatibleHeader, line, lineNo, false, mapping, Collections.emptyList(), results
                );

                final String nodeCsvId = (String) idAttribute.map(result.map::get).orElse(null);

                // if 'ignore duplicate nodes' is false, there is an id field and the mapping already has the current id,
                // we either fail the loading process or skip it depending on the 'ignore duplicate nodes' setting
                if (idField.isPresent() && idspaceIdMapping.containsKey(nodeCsvId)) {
                    if (clc.getIgnoreDuplicateNodes()) {
                        continue;
                    } else {
                        throw new IllegalStateException("Duplicate node with id " + nodeCsvId + " found on line "+lineNo+"\n"
                                                        +Arrays.toString(line));
                    }
                }

                // create node and add its id to the mapping
                final Node node = btx.getTransaction().createNode();
                if (idField.isPresent()) {
                    idspaceIdMapping.put(nodeCsvId, node.getId());
                }

                // add labels
                for (String label : labels) {
                    node.addLabel(Label.label(label));
                }

                // add properties
                int props = 0;
                for (CsvHeaderField field : fields) {
                    final String name = field.getName();
                    Object value = result.map.get(name);

                    if (field.isMeta()) {
                        final List<String> customLabels = (List<String>) value;
                        for (String customLabel : customLabels) {
                            node.addLabel(Label.label(customLabel));
                        }
                    } else if (field.isId()) {
                        final Object idValue;
                        if (clc.getStringIds()) {
                            idValue = value;
                        } else {
                            idValue = Long.valueOf((String) value);
                        }
                        node.setProperty(field.getName(), idValue);
                        props++;
                    } else {
                        boolean propertyAdded = CsvPropertyConverter.addPropertyToGraphEntity(node, field, value,clc);
                        props += propertyAdded ? 1 : 0;
                    }
                }
                reporter.update(1, 0, props++);
            }
        }
    }

    /**
     * Loads relationships from a CSV file with given relationship types to an online database,
     * using the {@code idMapping} created by the
     * {@link #loadNodes(String, List, GraphDatabaseService, Map)} method.
     *
     * @param fileName URI of the CSV file representing the relationship
     * @param type relationship type to be applied to each relationships
     * @param db running database instance
     * @param idMapping stores mapping between the CSV ids and the DB's internal node ids
     * @throws IOException
     */
    public void loadRelationships(
            final String fileName, final String type, final GraphDatabaseService db,
            final Map<String, Map<String, Long>> idMapping) throws IOException {
        final CountingReader reader = FileUtils.readerFor(fileName);
        final String header = readFirstLine(reader);
        final List<CsvHeaderField> fields = CsvHeaderFields.processHeader(header, clc.getDelimiter(), clc.getQuotationCharacter());

        final CsvHeaderField startIdField = fields.stream()
                .filter(f -> CsvLoaderConstants.START_ID_FIELD.equals(f.getType()))
                .findFirst().get();

        final CsvHeaderField endIdField = fields.stream()
                .filter(f -> CsvLoaderConstants.END_ID_FIELD.equals(f.getType()))
                .findFirst().get();

        final List<CsvHeaderField> edgePropertiesFields = fields.stream()
                .filter(field -> !CsvLoaderConstants.START_ID_FIELD.equals(field.getType()))
                .filter(field -> !CsvLoaderConstants.END_ID_FIELD.equals(field.getType()))
                .collect(Collectors.toList());

        final Map<String, Mapping> mapping = fields.stream().collect(
                Collectors.toMap(
                        CsvHeaderField::getName,
                        f -> {
                            final Map<String, Object> mappingMap = Collections
                                    .unmodifiableMap(Stream.of(
                                            new AbstractMap.SimpleEntry<>("type", f.getType()),
                                            new AbstractMap.SimpleEntry<>("array", f.isArray())
                                    ).collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)));

                            return new Mapping(f.getName(), mappingMap, clc.getArrayDelimiter(), false);
                        }
                )
        );

        final CSVReader csv = new CSVReader(reader, clc.getDelimiter());
        final String[] loadCsvCompatibleHeader = fields.stream().map(f -> f.getName()).toArray(String[]::new);

        int lineNo = 0;
        try (BatchTransaction btx = new BatchTransaction(db, clc.getBatchSize(), reporter)) {
            for (String[] line : csv.readAll()) {
                lineNo++;

                final EnumSet<Results> results = EnumSet.of(Results.map);
                final CSVResult result = new CSVResult(
                        loadCsvCompatibleHeader, line, lineNo, false, mapping, Collections.emptyList(), results
                );

                final Object startId = result.map.get(CsvLoaderConstants.START_ID_ATTR);
                final Object startInternalId = idMapping.get(startIdField.getIdSpace()).get(startId);
                if (startInternalId == null) {
                    throw new IllegalStateException("Node for id space " + endIdField.getIdSpace() + " and id " + startId + " not found");
                }
                final Node source = btx.getTransaction().getNodeById((long) startInternalId);

                final Object endId = result.map.get(CsvLoaderConstants.END_ID_ATTR);
                final Object endInternalId = idMapping.get(endIdField.getIdSpace()).get(endId);
                if (endInternalId == null) {
                    throw new IllegalStateException("Node for id space " + endIdField.getIdSpace() + " and id " + endId + " not found");
                }
                final Node target = btx.getTransaction().getNodeById((long) endInternalId);

                final String currentType;
                final Object overridingType = result.map.get(CsvLoaderConstants.TYPE_ATTR);
                if (overridingType != null && !((String) overridingType).isEmpty()) {
                    currentType = (String) overridingType;
                } else {
                    currentType = type;
                }
                final Relationship rel = source.createRelationshipTo(target, RelationshipType.withName(currentType));

                // add properties
                int props = 0;
                for (CsvHeaderField field : edgePropertiesFields) {
                    final String name = field.getName();
                    Object value = result.map.get(name);
                    boolean propertyAdded = CsvPropertyConverter.addPropertyToGraphEntity(rel, field, value, clc);
                    props += propertyAdded ? 1 : 0;
                }
                reporter.update(0, 1, props);
            }
        }
    }

    private static String readFirstLine(CountingReader reader) throws IOException {
        String line = "";
        int i;
        while ((i = reader.read()) != 0) {
            char c = (char) i;
            if (c == '\n') break;
            line += c;
        }
        return line;
    }

}
