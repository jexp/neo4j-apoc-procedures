package apoc.export.xls;

import apoc.ApocConfig;
import apoc.Extended;
import apoc.export.util.ExportConfig;
import apoc.export.util.NodesAndRelsSubGraph;
import apoc.export.util.ProgressReporter;
import apoc.result.ProgressInfo;
import apoc.util.Util;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.neo4j.cypher.export.DatabaseSubGraph;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.*;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.util.FileUtils.getOutputStream;

@Extended
public class ExportXls {
    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Context
    public ApocConfig apocConfig;

    @Procedure
    @Description("apoc.export.xls.all(file,config) - exports whole database as xls to the provided file")
    public Stream<ProgressInfo> all(@Name("file") String fileName, @Name("config") Map<String, Object> config) throws Exception {

        String source = String.format("database: nodes(%d), rels(%d)", Util.nodeCount(tx), Util.relCount(tx));
        return exportXls(fileName, source, new DatabaseSubGraph(tx), config);
    }

    @Procedure
    @Description("apoc.export.xls.data(nodes,rels,file,config) - exports given nodes and relationships as xls to the provided file")
    public Stream<ProgressInfo> data(@Name("nodes") List<Node> nodes, @Name("rels") List<Relationship> rels, @Name("file") String fileName, @Name("config") Map<String, Object> config) throws Exception {

        String source = String.format("data: nodes(%d), rels(%d)", nodes.size(), rels.size());
        return exportXls(fileName, source, new NodesAndRelsSubGraph(tx, nodes, rels), config);
    }

    @Procedure
    @Description("apoc.export.xls.graph(graph,file,config) - exports given graph object as xls to the provided file")
    public Stream<ProgressInfo> graph(@Name("graph") Map<String,Object> graph, @Name("file") String fileName, @Name("config") Map<String, Object> config) throws Exception {

        Collection<Node> nodes = (Collection<Node>) graph.get("nodes");
        Collection<Relationship> rels = (Collection<Relationship>) graph.get("relationships");
        String source = String.format("graph: nodes(%d), rels(%d)", nodes.size(), rels.size());
        return exportXls(fileName, source, new NodesAndRelsSubGraph(tx, nodes, rels), config);
    }

    @Procedure
    @Description("apoc.export.xls.query(query,file,{config,...,params:{params}}) - exports results from the cypher statement as xls to the provided file")
    public Stream<ProgressInfo> query(@Name("query") String query, @Name("file") String fileName, @Name("config") Map<String, Object> config) throws Exception {
        Map<String,Object> params = config == null ? Collections.emptyMap() : (Map<String,Object>)config.getOrDefault("params", Collections.emptyMap());
        Result result = tx.execute(query,params);
        String source = String.format("statement: cols(%d)", result.columns().size());
        return exportXls(fileName, source,result,config);
    }

    private Stream<ProgressInfo> exportXls(@Name("file") String fileName, String source, Object data, Map<String,Object> configMap) throws Exception {
        ExportConfig c = new ExportConfig(configMap);
        apocConfig.checkWriteAllowed(c);
        try (Transaction tx = db.beginTx();
             OutputStream out = getOutputStream(fileName, null);
             SXSSFWorkbook wb = new SXSSFWorkbook(-1)) {

            XlsExportConfig config = new XlsExportConfig(configMap);
            ProgressInfo progressInfo = new ProgressInfo(fileName, source, "xls");
            progressInfo.batchSize = config.getBatchSize();
            ProgressReporter reporter = new ProgressReporter(null, null, progressInfo);

            Map<Class, CellStyle> styles = buildCellStyles(config, wb);

            if (data instanceof SubGraph) {
                dumpSubGraph((SubGraph) data, config, reporter, wb, styles);

            } else if (data instanceof Result ) {
                Result result = (Result) data;
                dumpResult(result, config, wb, styles);
            } else {
                throw new UnsupportedOperationException("cannot handle " + data.getClass());
            }

            wb.write(out);
            wb.dispose();
            reporter.done();
            tx.commit();
            return reporter.stream();
        }
    }

    private void dumpResult(Result result, XlsExportConfig config, SXSSFWorkbook wb, Map<Class, CellStyle> styles) {
        SXSSFSheet sheet = wb.createSheet();
        sheet.trackAllColumnsForAutoSizing();

        // header row
        int columnNum = 0;
        int rowNum = 0;
        Row headerRow = sheet.createRow(rowNum++);

        for (String header: result.columns()) {
            Cell cell = headerRow.createCell(columnNum);
            sheet.autoSizeColumn(columnNum);
            cell.setCellValue(header);
            columnNum++;
        }

        while (result.hasNext()) {
            Map<String, Object> map = result.next();
            Row row = sheet.createRow(rowNum++);
            columnNum = 0;
            for (String header: result.columns()) {
                columnNum = amendCell(row, columnNum, map.get(header), config, styles);
            }
        }
    }

    private void dumpSubGraph(SubGraph subgraph, XlsExportConfig config, ProgressReporter reporter, SXSSFWorkbook wb, Map<Class, CellStyle> styles) {
        // what's in the triple used below?
        // left: sheet instance
        // middle: list of "magic" property keys: <id> for nodes, <startNodeId> and <endNodeId> for rels
        // right: list of  "normal" property keys
        Map<String, Triple<SXSSFSheet, List<String>, List<String>>> sheetAndPropsForName = new HashMap<>();

        for (Node node : subgraph.getNodes()) {
            final List<String> labels;
            if (config.isJoinLabels()) {
                labels = Collections.singletonList(StreamSupport.stream(node.getLabels().spliterator(), false)
                        .map(Label::name)
                        .collect(Collectors.joining(",")));
            } else {
                labels = StreamSupport.stream(node.getLabels().spliterator(), false)
                        .map(Label::name)
                        .collect(Collectors.toList());
            }
            for (String label :labels) {
                String labelName = (config.isPrefixSheetWithEntityType() ? "Node-" : "") + label;
                createRowForEntity(wb, sheetAndPropsForName, node, labelName, reporter, config, styles);
            }
        }
        for (Relationship relationship: subgraph.getRelationships()) {
            String relationshipType = (config.isPrefixSheetWithEntityType() ? "Rel-" : "") + relationship.getType().name();
            createRowForEntity(wb, sheetAndPropsForName, relationship, relationshipType, reporter, config,styles);
        }

        // spit out header lines
        for (Triple<SXSSFSheet,List<String>, List<String>> triple: sheetAndPropsForName.values()) {
            Sheet sheet = triple.getLeft();

            List<String> magicKeys = triple.getMiddle();
            List<String> keys = triple.getRight();
            Row row = sheet.getRow(0);
            int cellNum = 0;
            for (String key: ListUtils.union(magicKeys,keys)) {
                sheet.autoSizeColumn(cellNum);
                Cell cell = row.createCell(cellNum++);
                cell.setCellValue(key);

            }
        }
    }

    private Map<Class, CellStyle> buildCellStyles(XlsExportConfig config, SXSSFWorkbook wb) {
        CreationHelper createHelper = wb.getCreationHelper();
        CellStyle dateTimeCellStyle = wb.createCellStyle();
        dateTimeCellStyle.setDataFormat(createHelper.createDataFormat().getFormat(config.getDateTimeStyle()));
        CellStyle dateCellStyle = wb.createCellStyle();
        dateCellStyle.setDataFormat(createHelper.createDataFormat().getFormat(config.getDateStyle()));

        Map<Class, CellStyle> styles = new HashMap<>();
        styles.put(ZonedDateTime.class, dateTimeCellStyle);
        styles.put(LocalDate.class, dateCellStyle);
        return styles;
    }

    private void createRowForEntity(Workbook wb, Map<String, Triple<SXSSFSheet, List<String>, List<String>>> sheetAndPropsForName, Entity entity, String sheetName, ProgressReporter reporter, XlsExportConfig config, Map<Class, CellStyle> styles) {
        Triple<SXSSFSheet, List<String>, List<String>> triple = sheetAndPropsForName.computeIfAbsent(sheetName, s -> {
            SXSSFSheet sheet = (SXSSFSheet) wb.createSheet(sheetName);
            sheet.trackAllColumnsForAutoSizing();
            sheet.createRow(0); // placeholder for header line
            return Triple.of(
                    sheet,
                    entity instanceof Node ?
                            Arrays.asList(config.getHeaderNodeId()) :
                            Arrays.asList(config.getHeaderRelationshipId(), config.getHeaderStartNodeId(), config.getHeaderEndNodeId()),
                    new ArrayList<>());
        });
        Sheet sheet = triple.getLeft();
        List<String> propertyKeys = triple.getRight();

        int lastRowNum = sheet.getLastRowNum();
        Row row = sheet.createRow(lastRowNum+1);
        int cellNum = 0;
        SortedMap<String, Object> props = new TreeMap<>(entity.getAllProperties()); // copy props

        if (entity instanceof Node) {
            Node node = (Node) entity;
            Cell idCell = row.createCell(cellNum++);
            idCell.setCellValue(((Long)(node.getId())).doubleValue());
            reporter.update(1, 0, props.size());
        } else if (entity instanceof Relationship) {
            Relationship relationship = (Relationship) entity;
            Cell idCell = row.createCell(cellNum++);
            idCell.setCellValue(((Long)(relationship.getId())).doubleValue());
            Cell fromCell = row.createCell(cellNum++);
            fromCell.setCellValue(((Long)(relationship.getStartNodeId())).doubleValue());
            Cell toCell = row.createCell(cellNum++);
            toCell.setCellValue(((Long)(relationship.getEndNodeId())).doubleValue());
            reporter.update(0, 1, props.size());
        }

        // deal with property keys already being known
        for (String key: propertyKeys) {
            cellNum = amendCell(row, cellNum, props.remove(key), config, styles);
        }

        // add remaining properties as new keys
        for (String key: props.keySet()) {
            propertyKeys.add(key);
            cellNum = amendCell(row, cellNum, props.get(key), config, styles);
        }
    }

    private int amendCell(Row row, int cellNum, Object value, XlsExportConfig config, Map<Class, CellStyle> styles) {
        Cell cell = row.createCell(cellNum++);

        if (value == null) {
            cell.setCellType(CellType.BLANK);
        } else {

            CellStyle cellStyle = styles.get(value.getClass());
            if (cellStyle!=null) {
                cell.setCellStyle(cellStyle);
            }

            if (value instanceof String) {
                cell.setCellValue((String) value);
            } else if (value instanceof Number) {
                cell.setCellValue(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                cell.setCellValue(((Boolean) value).booleanValue());
            } else if (value instanceof String[]) {
                String[] values = (String[]) value;
                cell.setCellValue(Arrays.stream(values).collect(Collectors.joining(config.getArrayDelimiter())));
            } else if (value instanceof long[]) {
                long[] values = (long[]) value;
                cell.setCellValue(Arrays.stream(values).mapToObj(Long::toString).collect(Collectors.joining(config.getArrayDelimiter())));
            } else if (value instanceof double[]) {
                double[] values = (double[]) value;
                cell.setCellValue(Arrays.stream(values).mapToObj(Double::toString).collect(Collectors.joining(config.getArrayDelimiter())));
            } else if (value instanceof List) {
                List values = (List) value;
                String collect = ((Stream<String>) values.stream().map(x -> x.toString())).collect(Collectors.joining(config.getArrayDelimiter()));
                cell.setCellValue(collect);
            } else if (value instanceof LocalDate) {
                LocalDate localDate = (LocalDate) value;
                cell.setCellValue( Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            } else if (value instanceof ZonedDateTime) {
                ZonedDateTime zondedDateTime = (ZonedDateTime) value;
                cell.setCellValue( Date.from(zondedDateTime.toInstant()));
            } else {
                cell.setCellValue(value.toString());
                //throw new IllegalArgumentException("dunno know how to handle type " + value.getClass() + ". Please report this as a bug.");
            }
        }
        return cellNum;
    }

}
