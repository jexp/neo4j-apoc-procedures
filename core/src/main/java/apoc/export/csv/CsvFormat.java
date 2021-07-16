package apoc.export.csv;

import apoc.export.cypher.ExportFileManager;
import apoc.export.util.ExportConfig;
import apoc.export.util.Format;
import apoc.export.util.FormatUtils;
import apoc.export.util.MetaInformation;
import apoc.export.util.Reporter;
import apoc.result.ProgressInfo;
import com.opencsv.CSVWriter;
import org.apache.commons.lang.StringUtils;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.export.util.BulkImportUtil.formatHeader;
import static apoc.export.util.MetaInformation.collectPropTypesForNodes;
import static apoc.export.util.MetaInformation.collectPropTypesForRelationships;
import static apoc.export.util.MetaInformation.getLabelsString;
import static apoc.export.util.MetaInformation.updateKeyTypes;
import static apoc.util.Util.joinLabels;

/**
 * @author mh
 * @since 22.11.16
 */
public class CsvFormat implements Format {
    public static final String ID = "id";
    private final GraphDatabaseService db;
    private boolean applyQuotesToAll = true;

    private static final String[] NODE_HEADER_FIXED_COLUMNS = {"_id:id", "_labels:label"};
    private static final String[] REL_HEADER_FIXED_COLUMNS = {"_start:id", "_end:id", "_type:label"};

    public CsvFormat(GraphDatabaseService db) {
        this.db = db;
    }

    @Override
    public ProgressInfo load(Reader reader, Reporter reporter, ExportConfig config) throws Exception {
        return null;
    }

    @Override
    public ProgressInfo dump(SubGraph graph, ExportFileManager writer, Reporter reporter, ExportConfig config) {
        try (Transaction tx = db.beginTx()) {
            if (config.isBulkImport()) {
                writeAllBulkImport(graph, reporter, config, writer);
            } else {
                try (PrintWriter printWriter = writer.getPrintWriter("csv")) {
                    CSVWriter out = getCsvWriter(printWriter, config);
                    writeAll(graph, reporter, config, out);
                }
            }
            tx.commit();
            reporter.done();
            return reporter.getTotal();
        }
    }

    private CSVWriter getCsvWriter(Writer writer, ExportConfig config)
    {
        CSVWriter out;
        switch (config.isQuotes()) {
            case ExportConfig.NONE_QUOTES:
                out = new CSVWriter(writer,
                                    config.getDelimChar(),
                                    '\0', // quote char
                                    '\0', // escape char
                                    CSVWriter.DEFAULT_LINE_END);
                applyQuotesToAll = false;
                break;
            case ExportConfig.IF_NEEDED_QUUOTES:
                out = new CSVWriter(writer,
                                    config.getDelimChar(),
                                    ExportConfig.QUOTECHAR,
                                    '\0', // escape char
                                    CSVWriter.DEFAULT_LINE_END);
                applyQuotesToAll = false;
                break;
            case ExportConfig.ALWAYS_QUOTES:
            default:
                out = new CSVWriter(writer,
                                    config.getDelimChar(),
                                    ExportConfig.QUOTECHAR,
                                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                                    CSVWriter.DEFAULT_LINE_END);
                applyQuotesToAll = true;
                break;
        }
        return out;
    }

    public ProgressInfo dump(Result result, ExportFileManager writer, Reporter reporter, ExportConfig config) {
        try (Transaction tx = db.beginTx(); PrintWriter printWriter = writer.getPrintWriter("csv");) {
            CSVWriter out = getCsvWriter(printWriter, config);
            String[] header = writeResultHeader(result, out);

            String[] data = new String[header.length];
            result.accept((row) -> {
                for (int col = 0; col < header.length; col++) {
                    String key = header[col];
                    Object value = row.get(key);
                    data[col] = FormatUtils.toString(value);
                    reporter.update(value instanceof Node ? 1: 0,value instanceof Relationship ? 1: 0 , value instanceof Entity ? 0 : 1);
                }
                out.writeNext(data, applyQuotesToAll);
                reporter.nextRow();
                return true;
            });
            tx.commit();
            reporter.done();
            return reporter.getTotal();
        }
    }

    public String[] writeResultHeader(Result result, CSVWriter out) {
        List<String> columns = result.columns();
        int cols = columns.size();
        String[] header = columns.toArray(new String[cols]);
        out.writeNext(header, applyQuotesToAll);
        return header;
    }

    public void writeAll(SubGraph graph, Reporter reporter, ExportConfig config, CSVWriter out) {
        Map<String, Class> nodePropTypes = collectPropTypesForNodes(graph, db, config);
        Map<String, Class> relPropTypes = collectPropTypesForRelationships(graph, db, config);
        List<String> nodeHeader = generateHeader(nodePropTypes, config.useTypes(), NODE_HEADER_FIXED_COLUMNS);
        List<String> relHeader = generateHeader(relPropTypes, config.useTypes(), REL_HEADER_FIXED_COLUMNS);
        List<String> header = new ArrayList<>(nodeHeader);
        header.addAll(relHeader);
        out.writeNext(header.toArray(new String[header.size()]), applyQuotesToAll);
        int cols = header.size();

        writeNodes(graph, out, reporter, nodeHeader.subList(NODE_HEADER_FIXED_COLUMNS.length, nodeHeader.size()), cols, config.getBatchSize(), config.getDelim());
        writeRels(graph, out, reporter, relHeader.subList(REL_HEADER_FIXED_COLUMNS.length, relHeader.size()), cols, nodeHeader.size(), config.getBatchSize(), config.getDelim());
    }

    private void writeAllBulkImport(SubGraph graph, Reporter reporter, ExportConfig config, ExportFileManager writer) {
        Map<Iterable<Label>, List<Node>> objectNodes = StreamSupport.stream(graph.getNodes().spliterator(), false)
                .collect(Collectors.groupingBy(Node::getLabels));
        Map<RelationshipType, List<Relationship>> objectRels = StreamSupport.stream(graph.getRelationships().spliterator(), false)
                .collect(Collectors.groupingBy(Relationship::getType));
        writeNodesBulkImport(reporter, config, writer, objectNodes);
        writeRelsBulkImport(reporter, config, writer, objectRels);
    }

    private void writeNodesBulkImport(Reporter reporter, ExportConfig config, ExportFileManager writer, Map<Iterable<Label>, List<Node>> objectNode) {
        objectNode.entrySet().forEach(entrySet -> {
            Set<String> headerNode = generateHeaderNodeBulkImport(entrySet);

            List<List<String>> rows = entrySet.getValue()
                    .stream()
                    .map(n -> {
                        reporter.update(1, 0, n.getAllProperties().size());
                        return headerNode.stream().map(s -> {
                            if (s.equals(":LABEL")) {
                                return joinLabels(entrySet.getKey(), config.getArrayDelim());
                            }
                            String prop = s.split(":")[0];
                            return "".equals(prop) ? String.valueOf(n.getId()) : cleanPoint(FormatUtils.toString(n.getProperty(prop, "")));
                        }).collect(Collectors.toList());
                    })
                    .collect(Collectors.toList());

            String type = joinLabels(entrySet.getKey(), ".");
            writeRow(config, writer, headerNode, rows, "nodes." + type);
        });
    }

    private void writeRelsBulkImport(Reporter reporter, ExportConfig config, ExportFileManager writer, Map<RelationshipType, List<Relationship>> objectRel) {
        objectRel.entrySet().forEach(entrySet -> {
            Set<String> headerRel = generateHeaderRelationshipBulkImport(entrySet);

            List<List<String>> rows = entrySet.getValue()
                    .stream()
                    .map(r -> {
                        reporter.update(0, 1, r.getAllProperties().size());
                        return headerRel.stream().map(s -> {
                            switch (s) {
                                case ":START_ID":
                                    return String.valueOf(r.getStartNodeId());
                                case ":END_ID":
                                    return String.valueOf(r.getEndNodeId());
                                case ":TYPE":
                                    return entrySet.getKey().name();
                                default:
                                    String prop = s.split(":")[0];
                                    return "".equals(prop) ? String.valueOf(r.getId()) : cleanPoint(FormatUtils.toString(r.getProperty(prop, "")));
                            }
                        }).collect(Collectors.toList());
                    })
                    .collect(Collectors.toList());
            writeRow(config, writer, headerRel, rows, "relationships." + entrySet.getKey().name());
        });
    }

    private String cleanPoint(String point) {
        point = point.replace(",\"z\":null", "");
        point = point.replace(",\"heigth\":null", "");
        point = point.replace("\"", "");
        return point;
    }

    private Set<String> generateHeaderNodeBulkImport(Map.Entry<Iterable<Label>, List<Node>> entrySet) {
        Set<String> headerNode = new LinkedHashSet<>();
        headerNode.add(":ID");
        Map<String,Class> keyTypes = new LinkedHashMap<>();
        entrySet.getValue().forEach(node -> updateKeyTypes(keyTypes, node));
        final LinkedHashSet<String> otherFields = keyTypes.entrySet().stream()
                .map(stringClassEntry -> formatHeader(stringClassEntry))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        headerNode.addAll(otherFields);
        headerNode.add(":LABEL");
        return headerNode;
    }

    private Set<String> generateHeaderRelationshipBulkImport(Map.Entry<RelationshipType, List<Relationship>> entrySet) {
        Set<String> headerNode = new LinkedHashSet<>();
        Map<String,Class> keyTypes = new LinkedHashMap<>();
        entrySet.getValue().forEach(relationship -> updateKeyTypes(keyTypes, relationship));
        headerNode.add(":START_ID");
        headerNode.add(":END_ID");
        headerNode.add(":TYPE");
        headerNode.addAll(keyTypes.entrySet().stream().map(stringClassEntry -> formatHeader(stringClassEntry)).collect(Collectors.toCollection(LinkedHashSet::new)));
        return headerNode;
    }

    private void writeRow(ExportConfig config, ExportFileManager writer, Set<String> headerNode, List<List<String>> rows, String name) {
        try (PrintWriter pw = writer.getPrintWriter(name);
             CSVWriter csvWriter = getCsvWriter(pw, config)) {
            if (config.isSeparateHeader()) {
                try (PrintWriter pwHeader = writer.getPrintWriter("header." + name)) {
                    CSVWriter csvWriterHeader = getCsvWriter(pwHeader, config);
                    csvWriterHeader.writeNext(headerNode.toArray(new String[headerNode.size()]), false);
                }
            } else {
                csvWriter.writeNext(headerNode.toArray(new String[headerNode.size()]), false);
            }
            rows.forEach(row -> csvWriter.writeNext(row.toArray(new String[row.size()]), false));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> generateHeader(Map<String, Class> propTypes, boolean useTypes, String... starters) {
        List<String> result = new ArrayList<>();
        if (useTypes) {
            Collections.addAll(result, starters);
        } else {
            result.addAll(Stream.of(starters).map(s -> s.split(":")[0]).collect(Collectors.toList()));
        }
        result.addAll(propTypes.entrySet().stream()
                .map(entry -> {
                    String type = MetaInformation.typeFor(entry.getValue(), null);
                    return (type == null || type.equals("string") || !useTypes)
                            ? entry.getKey() : entry.getKey() + ":" + type;
                })
                .sorted()
                .collect(Collectors.toList()));
        return result;
    }

    private void writeNodes(SubGraph graph, CSVWriter out, Reporter reporter, List<String> header, int cols, int batchSize, String delimiter) {
        String[] row=new String[cols];
        int nodes = 0;
        for (Node node : graph.getNodes()) {
            row[0]=String.valueOf(node.getId());
            row[1]=getLabelsString(node);
            collectProps(header, node, reporter, row, 2, delimiter);
            out.writeNext(row, applyQuotesToAll);
            nodes++;
            if (batchSize==-1 || nodes % batchSize == 0) {
                reporter.update(nodes, 0, 0);
                nodes = 0;
            }
        }
        if (nodes>0) {
            reporter.update(nodes, 0, 0);
        }
    }

    private void collectProps(Collection<String> fields, Entity pc, Reporter reporter, String[] row, int offset, String delimiter) {
        for (String field : fields) {
            if (pc.hasProperty(field)) {
                row[offset] = FormatUtils.toString(pc.getProperty(field));
                reporter.update(0,0,1);
            }
            else {
                row[offset] = "";
            }
            offset++;
        }
    }

    private void writeRels(SubGraph graph, CSVWriter out, Reporter reporter, List<String> relHeader, int cols, int offset, int batchSize, String delimiter) {
        String[] row=new String[cols];
        int rels = 0;
        for (Relationship rel : graph.getRelationships()) {
            row[offset]=String.valueOf(rel.getStartNode().getId());
            row[offset+1]=String.valueOf(rel.getEndNode().getId());
            row[offset+2]=rel.getType().name();
            collectProps(relHeader, rel, reporter, row, 3 + offset, delimiter);
            out.writeNext(row, applyQuotesToAll);
            rels++;
            if (batchSize==-1 || rels % batchSize == 0) {
                reporter.update(0, rels, 0);
                rels = 0;
            }
        }
        if (rels > 0) {
            reporter.update(0, rels, 0);
        }
    }
}
