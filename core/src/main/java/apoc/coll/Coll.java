package apoc.coll;

import apoc.result.ListResult;
import com.google.common.util.concurrent.AtomicDouble;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.util.Combinations;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;

import java.lang.reflect.Array;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;

public class Coll {

    public static final char ASCENDING_ORDER_CHAR = '^';

    @Context
    public GraphDatabaseService db;

    @Context public Transaction tx;

    @UserFunction
    @Description("apoc.coll.stdev(list, isBiasCorrected) - returns the sample or population standard deviation with isBiasCorrected true or false respectively. For example apoc.coll.stdev([10, 12, 23]) return 7")
    public Number stdev(@Name("list") List<Number> list, @Name(value = "isBiasCorrected", defaultValue = "true") boolean isBiasCorrected) {
        if (list == null || list.isEmpty()) return null;
        final double stdev = new StandardDeviation(isBiasCorrected)
                .evaluate(list.stream().mapToDouble(Number::doubleValue).toArray());
        if ((long) stdev == stdev) return (long) stdev;
        return stdev;
    }

    @UserFunction
    @Description("apoc.coll.runningTotal(list1) - returns an accumulative array. For example apoc.coll.runningTotal([1,2,3.5]) return [1,3,6.5]")
    public List<Number> runningTotal(@Name("list") List<Number> list) {
        if (list == null || list.isEmpty()) return null;
        AtomicDouble sum = new AtomicDouble();
        return list.stream().map(i -> {
                    double value = sum.addAndGet(i.doubleValue());
                    if (value == sum.longValue()) return sum.longValue();
                    return value;
                }).collect(Collectors.toList());
    }

    @Procedure
    @Description("apoc.coll.zipToRows(list1,list2) - creates pairs like zip but emits one row per pair")
    public Stream<ListResult> zipToRows(@Name("list1") List<Object> list1, @Name("list2") List<Object> list2) {
        if (list1.isEmpty()) return Stream.empty();
        ListIterator<Object> it = list2.listIterator();
        return list1.stream().map((e) -> new ListResult(asList(e,it.hasNext() ? it.next() : null)));
    }

    @UserFunction
    @Description("apoc.coll.zip([list1],[list2])")
    public List<List<Object>> zip(@Name("list1") List<Object> list1, @Name("list2") List<Object> list2) {
		if (list1 == null || list2 == null) return null;
		if (list1.isEmpty() || list2.isEmpty()) return Collections.emptyList();
        List<List<Object>> result = new ArrayList<>(list1.size());
        ListIterator it = list2.listIterator();
        for (Object o1 : list1) {
            result.add(asList(o1,it.hasNext() ? it.next() : null));
        }
        return result;
    }

    @UserFunction
    @Description("apoc.coll.pairs([1,2,3]) returns [1,2],[2,3],[3,null] ")
    public List<List<Object>> pairs(@Name("list") List<Object> list) {
		if (list == null) return null;
		if (list.isEmpty()) return Collections.emptyList();
        return zip(list,list.subList(1,list.size()));
    }
    @UserFunction
    @Description("apoc.coll.pairsMin([1,2,3]) returns [1,2],[2,3]")
    public List<List<Object>> pairsMin(@Name("list") List<Object> list) {
		if (list == null) return null;
		if (list.isEmpty()) return Collections.emptyList();
        return zip(list.subList(0,list.size()-1),list.subList(1,list.size()));
    }

    @UserFunction
    @Description("apoc.coll.sum([0.5,1,2.3])")
    public Double sum(@Name("numbers") List<Number> list) {
		if (list == null || list.isEmpty()) return null;
        double sum = 0;
        for (Number number : list) {
            sum += number.doubleValue();
        }
        return sum;
    }

    @UserFunction
    @Description("apoc.coll.avg([0.5,1,2.3])")
    public Double avg(@Name("numbers") List<Number> list) {
		if (list == null || list.isEmpty()) return null;
        double avg = 0;
        for (Number number : list) {
            avg += number.doubleValue();
        }
        return (avg/(double)list.size());
    }
    @UserFunction
    @Description("apoc.coll.min([0.5,1,2.3])")
    public Object min(@Name("values") List<Object> list) {
		if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);

        try (Result result = tx.execute("cypher runtime=slotted return reduce(res=null, x in $list | CASE WHEN res IS NULL OR x<res THEN x ELSE res END) as value", Collections.singletonMap("list", list))) {
            return result.next().get("value");
        }
    }

    @UserFunction
    @Description("apoc.coll.max([0.5,1,2.3])")
    public Object max(@Name("values") List<Object> list) {
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        try (Result result = tx.execute("cypher runtime=slotted return reduce(res=null, x in $list | CASE WHEN res IS NULL OR res<x THEN x ELSE res END) as value", Collections.singletonMap("list", list))) {
            return result.next().get("value");
        }
    }

    @Procedure
    @Description("apoc.coll.elements(list,limit,offset) yield _1,_2,..,_10,_1s,_2i,_3f,_4m,_5l,_6n,_7r,_8p - deconstruct subset of mixed list into identifiers of the correct type")
    public Stream<ElementsResult> elements(@Name("values") List<Object> list, @Name(value = "limit",defaultValue = "-1") long limit,@Name(value = "offset",defaultValue = "0") long offset) {
        int elements =  (limit < 0 ? list.size() : Math.min((int)(offset+limit),list.size())) - (int)offset;
        if (elements > ElementsResult.MAX_ELEMENTS) elements = ElementsResult.MAX_ELEMENTS;
        ElementsResult result = new ElementsResult();
        for (int i=0;i<elements;i++) {
            result.add(list.get((int)offset+i));
        }
        return Stream.of(result);
    }

    public static class ElementsResult {
        public Object       _1,_2,_3,_4,_5,_6,_7,_8,_9,_10;
        public String       _1s,_2s,_3s,_4s,_5s,_6s,_7s,_8s,_9s,_10s;
        public Long         _1i,_2i,_3i,_4i,_5i,_6i,_7i,_8i,_9i,_10i;
        public Double       _1f,_2f,_3f,_4f,_5f,_6f,_7f,_8f,_9f,_10f;
        public Boolean      _1b,_2b,_3b,_4b,_5b,_6b,_7b,_8b,_9b,_10b;
        public List<Object> _1l,_2l,_3l,_4l,_5l,_6l,_7l,_8l,_9l,_10l;
        public Map<String,Object> _1m,_2m,_3m,_4m,_5m,_6m,_7m,_8m,_9m,_10m;
        public Node         _1n,_2n,_3n,_4n,_5n,_6n,_7n,_8n,_9n,_10n;
        public Relationship _1r,_2r,_3r,_4r,_5r,_6r,_7r,_8r,_9r,_10r;
        public Path         _1p,_2p,_3p,_4p,_5p,_6p,_7p,_8p,_9p,_10p;
        public long         elements;
        static final int MAX_ELEMENTS = 10;
        void add(Object o) {
            if (elements==MAX_ELEMENTS) return;
            setObject(o, (int) elements);
            if (o instanceof String) {
                setString((String)o, (int) elements);
            }
            if (o instanceof Number) {
                setLong(((Number)o).longValue(), (int) elements);
                setDouble(((Number)o).doubleValue(), (int) elements);
            }
            if (o instanceof Boolean) {
                setBoolean((Boolean)o, (int) elements);
            }
            if (o instanceof Map) {
                setMap((Map)o, (int)elements);
            }
            if (o instanceof Map) {
                setMap((Map)o, (int)elements);
            }
            if (o instanceof List) {
                setList((List)o, (int)elements);
            }
            if (o instanceof Node) {
                setNode((Node)o, (int)elements);
            }
            if (o instanceof Relationship) {
                setRelationship((Relationship)o, (int)elements);
            }
            if (o instanceof Path) {
                setPath((Path)o, (int)elements);
            }
            elements++;
        }

        public void setObject(Object o, int idx) {
            switch (idx) {
                case 0: _1 = o; break;
                case 1: _2 = o; break;
                case 2: _3 = o; break;
                case 3: _4 = o; break;
                case 4: _5 = o; break;
                case 5: _6 = o; break;
                case 6: _7 = o; break;
                case 7: _8 = o; break;
                case 8: _9 = o; break;
                case 9: _10= o; break;
            }
        }
        public void setString(String o, int idx) {
            switch (idx) {
                case 0: _1s = o; break;
                case 1: _2s = o; break;
                case 2: _3s = o; break;
                case 3: _4s = o; break;
                case 4: _5s = o; break;
                case 5: _6s = o; break;
                case 6: _7s = o; break;
                case 7: _8s = o; break;
                case 8: _9s = o; break;
                case 9: _10s= o; break;
            }
        }
        public void setLong(Long o, int idx) {
            switch (idx) {
                case 0: _1i = o; break;
                case 1: _2i = o; break;
                case 2: _3i = o; break;
                case 3: _4i = o; break;
                case 4: _5i = o; break;
                case 5: _6i = o; break;
                case 6: _7i = o; break;
                case 7: _8i = o; break;
                case 8: _9i = o; break;
                case 9: _10i= o; break;
            }
        }
        public void setBoolean(Boolean o, int idx) {
            switch (idx) {
                case 0: _1b = o; break;
                case 1: _2b = o; break;
                case 2: _3b = o; break;
                case 3: _4b = o; break;
                case 4: _5b = o; break;
                case 5: _6b = o; break;
                case 6: _7b = o; break;
                case 7: _8b = o; break;
                case 8: _9b = o; break;
                case 9: _10b= o; break;
            }
        }
        public void setDouble(Double o, int idx) {
            switch (idx) {
                case 0: _1f = o; break;
                case 1: _2f = o; break;
                case 2: _3f = o; break;
                case 3: _4f = o; break;
                case 4: _5f = o; break;
                case 5: _6f = o; break;
                case 6: _7f = o; break;
                case 7: _8f = o; break;
                case 8: _9f = o; break;
                case 9: _10f= o; break;
            }
        }
        public void setNode(Node o, int idx) {
            switch (idx) {
                case 0: _1n = o; break;
                case 1: _2n = o; break;
                case 2: _3n = o; break;
                case 3: _4n = o; break;
                case 4: _5n = o; break;
                case 5: _6n = o; break;
                case 6: _7n = o; break;
                case 7: _8n = o; break;
                case 8: _9n = o; break;
                case 9: _10n= o; break;
            }
        }
        public void setRelationship(Relationship o, int idx) {
            switch (idx) {
                case 0: _1r = o; break;
                case 1: _2r = o; break;
                case 2: _3r = o; break;
                case 3: _4r = o; break;
                case 4: _5r = o; break;
                case 5: _6r = o; break;
                case 6: _7r = o; break;
                case 7: _8r = o; break;
                case 8: _9r = o; break;
                case 9: _10r= o; break;
            }
        }
        public void setPath(Path o, int idx) {
            switch (idx) {
                case 0: _1p = o; break;
                case 1: _2p = o; break;
                case 2: _3p = o; break;
                case 3: _4p = o; break;
                case 4: _5p = o; break;
                case 5: _6p = o; break;
                case 6: _7p = o; break;
                case 7: _8p = o; break;
                case 8: _9p = o; break;
                case 9: _10p= o; break;
            }
        }
        public void setMap(Map o, int idx) {
            switch (idx) {
                case 0: _1m = o; break;
                case 1: _2m = o; break;
                case 2: _3m = o; break;
                case 3: _4m = o; break;
                case 4: _5m = o; break;
                case 5: _6m = o; break;
                case 6: _7m = o; break;
                case 7: _8m = o; break;
                case 8: _9m = o; break;
                case 9: _10m= o; break;
            }
        }
        public void setList(List o, int idx) {
            switch (idx) {
                case 0: _1l = o; break;
                case 1: _2l = o; break;
                case 2: _3l = o; break;
                case 3: _4l = o; break;
                case 4: _5l = o; break;
                case 5: _6l = o; break;
                case 6: _7l = o; break;
                case 7: _8l = o; break;
                case 8: _9l = o; break;
                case 9: _10l= o; break;
            }
        }
    }

    @Procedure
    @Description("apoc.coll.partition(list,batchSize)")
    public Stream<ListResult> partition(@Name("values") List<Object> list, @Name("batchSize") long batchSize) {
	    if (list==null || list.isEmpty()) return Stream.empty();
        return partitionList(list, (int) batchSize).map(ListResult::new);
    }

    @UserFunction("apoc.coll.partition")
    @Description("apoc.coll.partition(list,batchSize)")
    public List<Object> partitionFn(@Name("values") List<Object> list, @Name("batchSize") long batchSize) {
        if (list==null || list.isEmpty()) return new ArrayList<>();
        return partitionList(list, (int) batchSize).collect(Collectors.toList());
    }

    @Procedure
    @Description("apoc.coll.split(list,value) | splits collection on given values rows of lists, value itself will not be part of resulting lists")
    public Stream<ListResult> split(@Name("values") List<Object> list, @Name("value") Object value) {
	    if (list==null || list.isEmpty()) return Stream.empty();
        List<Object> l = new ArrayList<>(list);
        List<List<Object>> result = new ArrayList<>(10);
        int idx = l.indexOf(value);
        while (idx != -1) {
            List<Object> subList = l.subList(0, idx);
            if (!subList.isEmpty()) result.add(subList);
            l = l.subList(idx+1,l.size());
            idx = l.indexOf(value);
        }
        if (!l.isEmpty()) result.add(l);
        return result.stream().map(ListResult::new);
    }

    private Stream<List<Object>> partitionList(@Name("values") List list, @Name("batchSize") int batchSize) {
        int total = list.size();
        int pages = total % batchSize == 0 ? total/batchSize : total/batchSize + 1;
        return IntStream.range(0, pages).parallel().boxed()
                .map(page -> {
                    int from = page * batchSize;
                    return list.subList(from, Math.min(from + batchSize, total));
                });
    }

    @UserFunction
    @Description("apoc.coll.contains(coll, value) optimized contains operation (using a HashSet) (returns single row or not)")
    public boolean contains(@Name("coll") List<Object> coll, @Name("value") Object value) {
        if (coll == null || coll.isEmpty()) return false;
        return  new HashSet<>(coll).contains(value);
//        int batchSize = 250;
//        boolean result = (coll.size() < batchSize) ? coll.contains(value) : partitionList(coll, batchSize).parallel().anyMatch(list -> list.contains(value));
    }

    @UserFunction
    @Description("apoc.coll.set(coll, index, value) | set index to value")
    public List<Object> set(@Name("coll") List<Object> coll, @Name("index") long index, @Name("value") Object value) {
        if (coll == null) return null;
        if (index < 0 || value == null || index >= coll.size()) return coll;

        List<Object> list = new ArrayList<>(coll);
        list.set( (int) index, value );
        return list;
    }

    @UserFunction
    @Description("apoc.coll.insert(coll, index, value) | insert value at index")
    public List<Object> insert(@Name("coll") List<Object> coll, @Name("index") long index, @Name("value") Object value) {
        if (coll == null) return null;
        if (index < 0 || value == null || index > coll.size()) return coll;

        List<Object> list = new ArrayList<>(coll);
        list.add( (int) index, value );
        return list;
    }

    @UserFunction
    @Description("apoc.coll.insertAll(coll, index, values) | insert values at index")
    public List<Object> insertAll(@Name("coll") List<Object> coll, @Name("index") long index, @Name("values") List<Object> values) {
        if (coll == null) return null;
        if (index < 0 || values == null || values.isEmpty() || index > coll.size()) return coll;

        List<Object> list = new ArrayList<>(coll);
        list.addAll( (int) index, values );
        return list;
    }

    @UserFunction
    @Description("apoc.coll.remove(coll, index, [length=1]) | remove range of values from index to length")
    public List<Object> remove(@Name("coll") List<Object> coll, @Name("index") long index, @Name(value = "length",defaultValue = "1") long length) {
        if (coll == null) return null;
        if (index < 0 || index >= coll.size() || length <= 0) return coll;

        List<Object> list = new ArrayList<>(coll);
        for (long i = index+length-1; i >= index; i--)
        {
            if (i < list.size()) list.remove( (int) i );
        }
        return list;
    }

    @UserFunction
    @Description("apoc.coll.indexOf(coll, value) | position of value in the list")
    public long indexOf(@Name("coll") List<Object> coll, @Name("value") Object value) {
        // return reduce(res=[0,-1], x in $list | CASE WHEN x=$value AND res[1]=-1 THEN [res[0], res[0]+1] ELSE [res[0]+1, res[1]] END)[1] as value
        if (coll == null || coll.isEmpty()) return -1;
        return  new ArrayList<>(coll).indexOf(value);
    }

    @UserFunction
    @Description("apoc.coll.containsAll(coll, values) optimized contains-all operation (using a HashSet) (returns single row or not)")
    public boolean containsAll(@Name("coll") List<Object> coll, @Name("values") List<Object> values) {
        if (coll == null || coll.isEmpty() || values == null) return false;
        return new HashSet<>(coll).containsAll(values);
    }

    @UserFunction
    @Description("apoc.coll.containsSorted(coll, value) optimized contains on a sorted list operation (Collections.binarySearch) (returns single row or not)")
    public boolean containsSorted(@Name("coll") List<Object> coll, @Name("value") Object value) {
        if (coll == null || coll.isEmpty()) return false;
        int batchSize = 5000-1; // Collections.binarySearchThreshold
        List list = (coll instanceof RandomAccess || coll.size() < batchSize) ? coll : new ArrayList(coll);
        return Collections.binarySearch(list, value) >= 0;
//        Predicate<List> contains = l -> Collections.binarySearch(l, value) >= 0;
//        boolean result = (list.size() < batchSize) ? contains.test(list) : partitionList(list, batchSize).parallel().anyMatch(contains);
    }

    @UserFunction
    @Description("apoc.coll.containsAllSorted(coll, value) optimized contains-all on a sorted list operation (Collections.binarySearch) (returns single row or not)")
    public boolean containsAllSorted(@Name("coll") List<Object> coll, @Name("values") List<Object> values) {
        if (coll == null || values == null) return false;
        int batchSize = 5000-1; // Collections.binarySearchThreshold
        List list = (coll instanceof RandomAccess || coll.size() < batchSize) ? coll : new ArrayList(coll);
        for (Object value : values) {
            boolean result = Collections.binarySearch(list, value) >= 0;
            if (!result) return false;
        }
        return true;
    }

    @UserFunction
    @Description("apoc.coll.isEqualCollection(coll, values) return true if two collections contain the same elements with the same cardinality in any order (using a HashMap)")
    public boolean isEqualCollection(@Name("coll") List<Object> first, @Name("values") List<Object> second) {
        if (first == null && second == null) return true;
        if (first == null || second == null || first.size() != second.size()) return false;

        Map<Object, Long> map1 = first.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<Object, Long> map2 = second.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return map1.equals(map2);
    }

    @UserFunction
    @Description("apoc.coll.toSet([list]) returns a unique list backed by a set")
    public List<Object> toSet(@Name("values") List<Object> list) {
	    if (list == null) return null;
        return new SetBackedList(new LinkedHashSet(list));
    }

    @UserFunction
    @Description("apoc.coll.sumLongs([1,3,3])")
    public Long sumLongs(@Name("numbers") List<Number> list) {
        if (list == null) return null;
        long sum = 0;
        for (Number number : list) {
            sum += number.longValue();
        }
        return sum;
    }

    @UserFunction
    @Description("apoc.coll.sort(coll) sort on Collections")
    public List<Object> sort(@Name("coll") List<Object> coll) {
	    if (coll == null || coll.isEmpty()) return Collections.emptyList();
        List sorted = new ArrayList<>(coll);
        Collections.sort((List<? extends Comparable>) sorted);
        return sorted;
    }

    @UserFunction
    @Description("apoc.coll.sortNodes([nodes], 'name') sort nodes by property")
    public List<Node> sortNodes(@Name("coll") List<Node> coll, @Name("prop") String prop) {
	    if (coll == null || coll.isEmpty()) return Collections.emptyList();
        List<Node> sorted = new ArrayList<>(coll);
        int reverseOrder = reverseOrder(prop);
        String cleanedProp = cleanProperty(prop);
        Collections.sort(sorted, (x, y) -> reverseOrder * compare(x.getProperty(cleanedProp, null), y.getProperty(cleanedProp, null)));
        return sorted;
    }

    @UserFunction
    @Description("apoc.coll.sortMaps([maps], 'name') - sort maps by property")
    public List<Map<String,Object>> sortMaps(@Name("coll") List<Map<String,Object>> coll, @Name("prop") String prop) {
	    if (coll == null || coll.isEmpty()) return Collections.emptyList();
        List<Map<String,Object>> sorted = new ArrayList<>(coll);
        int reverseOrder = reverseOrder(prop);
        String cleanedProp = cleanProperty(prop);
        Collections.sort(sorted, (x, y) -> reverseOrder * compare(x.get(cleanedProp), y.get(cleanedProp)));
        return sorted;
    }

    public int reverseOrder(String prop) {
        return prop.charAt(0) == ASCENDING_ORDER_CHAR ? 1 : -1;
    }

    public String cleanProperty(String prop) {
        return prop.charAt(0) == ASCENDING_ORDER_CHAR ? prop.substring(1) : prop;
    }

    public static int compare(Object o1, Object o2) {
        if (o1 == null) return o2 == null ? 0 : -1;
        if (o2 == null) return 1;
        if (o1.equals(o2)) return 0;
        if (o1 instanceof Number && o2 instanceof Number) {
            if (o1 instanceof Double || o2 instanceof Double || o1 instanceof Float || o2 instanceof Float)
                return Double.compare(((Number) o1).doubleValue(), ((Number) o2).doubleValue());
            return Long.compare(((Number) o1).longValue(), ((Number) o2).longValue());
        }
        if (o1 instanceof Boolean && o2 instanceof Boolean) return ((Boolean) o1) ? 1 : -1;
        if (o1 instanceof Node && o2 instanceof Node) return Long.compare(((Node)o1).getId(),((Node)o2).getId());
        if (o1 instanceof Relationship && o2 instanceof Relationship) return Long.compare(((Relationship)o1).getId(),((Relationship)o2).getId());
        return o1.toString().compareTo(o2.toString());
    }

    @UserFunction
    @Description("apoc.coll.union(first, second) - creates the distinct union of the 2 lists")
    public List<Object> union(@Name("first") List<Object> first, @Name("second") List<Object> second) {
		if (first == null) return second;
		if (second == null) return first;
        Set<Object> set = new HashSet<>(first);
        set.addAll(second);
        return new SetBackedList(set);
    }
    @UserFunction
    @Description("apoc.coll.subtract(first, second) - returns unique set of first list with all elements of second list removed")
    public List<Object> subtract(@Name("first") List<Object> first, @Name("second") List<Object> second) {
		if (first == null) return null;
        Set<Object> set = new HashSet<>(first);
        if (second!=null) set.removeAll(second);
        return new SetBackedList(set);
    }
    @UserFunction
    @Description("apoc.coll.removeAll(first, second) - returns first list with all elements of second list removed")
    public List<Object> removeAll(@Name("first") List<Object> first, @Name("second") List<Object> second) {
		if (first == null) return null;
        List<Object> list = new ArrayList<>(first);
        if (second!=null) list.removeAll(second);
        return list;
    }

    @UserFunction
    @Description("apoc.coll.intersection(first, second) - returns the unique intersection of the two lists")
    public List<Object> intersection(@Name("first") List<Object> first, @Name("second") List<Object> second) {
        if (first == null || second == null) return Collections.emptyList();
        Set<Object> set = new HashSet<>(first);
        set.retainAll(second);
        return new SetBackedList(set);
    }

    @UserFunction
    @Description("apoc.coll.disjunction(first, second) - returns the disjunct set of the two lists")
    public List<Object> disjunction(@Name("first") List<Object> first, @Name("second") List<Object> second) {
        if (first == null) return second;
        if (second == null) return first;
        Set<Object> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        Set<Object> set = new HashSet<>(first);
        set.addAll(second);
        set.removeAll(intersection);
        return new SetBackedList(set);
    }
    @UserFunction
    @Description("apoc.coll.unionAll(first, second) - creates the full union with duplicates of the two lists")
    public List<Object> unionAll(@Name("first") List<Object> first, @Name("second") List<Object> second) {
        if (first == null) return second;
        if (second == null) return first;
        List<Object> list = new ArrayList<>(first);
        list.addAll(second);
        return list;
    }

    @UserFunction
    @Description("apoc.coll.shuffle(coll) - returns the shuffled list")
    public List<Object> shuffle(@Name("coll") List<Object> coll) {
        if (coll == null || coll.isEmpty()) {
            return Collections.emptyList();
        } else if (coll.size() == 1) {
            return coll;
        }

        List<Object> shuffledList = new ArrayList<>(coll);
        Collections.shuffle(shuffledList);
        return shuffledList;
    }

    @UserFunction
    @Description("apoc.coll.randomItem(coll)- returns a random item from the list, or null on an empty or null list")
    public Object randomItem(@Name("coll") List<Object> coll) {
        if (coll == null || coll.isEmpty()) {
            return null;
        } else if (coll.size() == 1) {
            return coll.get(0);
        }

        return coll.get(ThreadLocalRandom.current().nextInt(coll.size()));
    }

    @UserFunction
    @Description("apoc.coll.randomItems(coll, itemCount, allowRepick: false) - returns a list of itemCount random items from the original list, optionally allowing picked elements to be picked again")
    public List<Object> randomItems(@Name("coll") List<Object> coll, @Name("itemCount") long itemCount, @Name(value = "allowRepick", defaultValue = "false") boolean allowRepick) {
        if (coll == null || coll.isEmpty() || itemCount <= 0) {
            return Collections.emptyList();
        }

        List<Object> pickList = new ArrayList<>(coll);
        List<Object> randomItems = new ArrayList<>((int)itemCount);
        Random random = ThreadLocalRandom.current();

        if (!allowRepick && itemCount >= coll.size()) {
            Collections.shuffle(pickList);
            return pickList;
        }

        while (randomItems.size() < itemCount) {
            Object item = allowRepick ? pickList.get(random.nextInt(pickList.size()))
                    : pickList.remove(random.nextInt(pickList.size()));
            randomItems.add(item);
        }

        return randomItems;
    }
    @UserFunction
    @Description("apoc.coll.containsDuplicates(coll) - returns true if a collection contains duplicate elements")
    public boolean containsDuplicates(@Name("coll") List<Object> coll) {
        if (coll == null || coll.size() <= 1) {
            return false;
        }

        Set<Object> set = new HashSet<>(coll);
        return set.size() < coll.size();
    }

    @UserFunction
    @Description("apoc.coll.duplicates(coll) - returns a list of duplicate items in the collection")
    public List<Object> duplicates(@Name("coll") List<Object> coll) {
        if (coll == null || coll.size() <= 1) {
            return Collections.emptyList();
        }

        Set<Object> set = new HashSet<>(coll.size());
        Set<Object> duplicates = new LinkedHashSet<>();

        for (Object obj : coll) {
            if (!set.add(obj)) {
                duplicates.add(obj);
            }
        }

        return new ArrayList(duplicates);
    }

    @UserFunction
    @Description("apoc.coll.duplicatesWithCount(coll) - returns a list of duplicate items in the collection and their count, keyed by `item` and `count` (e.g., `[{item: xyz, count:2}, {item:zyx, count:5}]`)")
    public List<Map<String, Object>> duplicatesWithCount(@Name("coll") List<Object> coll) {
        if (coll == null || coll.size() <= 1) {
            return Collections.emptyList();
        }

        // mimicking a counted bag
        Map<Object, MutableInt> duplicates = new LinkedHashMap<>(coll.size());
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (Object obj : coll) {
            MutableInt counter = duplicates.get(obj);
            if (counter == null) {
                counter = new MutableInt();
                duplicates.put(obj, counter);
            }
            counter.increment();
        }

        duplicates.forEach((o, intCounter) -> {
            int count = intCounter.intValue();
            if (count > 1) {
                Map<String, Object> entry = new LinkedHashMap<>(2);
                entry.put("item", o);
                entry.put("count", Long.valueOf(count));
                resultList.add(entry);
            }
        });

        return resultList;
    }

    @UserFunction
    @Description("apoc.coll.frequencies(coll) - returns a list of frequencies of the items in the collection, keyed by `item` and `count` (e.g., `[{item: xyz, count:2}, {item:zyx, count:5}, {item:abc, count:1}]`)")
    public List<Map<String, Object>> frequencies(@Name("coll") List<Object> coll) {
        if (coll == null || coll.size() == 0) {
            return Collections.emptyList();
        }

        // mimicking a counted bag
        Map<Object, MutableInt> counts = new LinkedHashMap<>(coll.size());
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (Object obj : coll) {
            MutableInt counter = counts.get(obj);
            if (counter == null) {
                counter = new MutableInt();
                counts.put(obj, counter);
            }
            counter.increment();
        }

        counts.forEach((o, intCounter) -> {
            int count = intCounter.intValue();
            Map<String, Object> entry = new LinkedHashMap<>(2);
            entry.put("item", o);
            entry.put("count", Long.valueOf(count));
            resultList.add(entry);
        });

        return resultList;
    }

    @UserFunction
    @Description("apoc.coll.frequenciesAsMap(coll) - return a map of frequencies of the items in the collection, key `item`, value `count` (e.g., `{1:2, 2:1}`)")
    public Map<String, Object> frequenciesAsMap(@Name("coll") List<Object> coll) {
	    if (coll == null) return Collections.emptyMap();
        return frequencies(coll).stream().collect(Collectors.toMap(t -> t.get("item").toString(), v-> v.get("count")));
    }

    @UserFunction
    @Description("apoc.coll.occurrences(coll, item) - returns the count of the given item in the collection")
    public long occurrences(@Name("coll") List<Object> coll, @Name("item") Object item) {
        if (coll == null || coll.isEmpty()) {
            return 0;
        }

        long occurrences = 0;

        for (Object obj : coll) {
            if (item.equals(obj)) {
                occurrences++;
            }
        }

        return occurrences;
    }


    @UserFunction
    @Description("apoc.coll.flatten(coll, [recursive]) - flattens list (nested if recursive is true)")
    public List<Object> flatten(@Name("coll") List<Object> coll,  @Name(value="recursive", defaultValue = "false") boolean recursive) {
        if (coll == null) return Collections.emptyList();
        if (recursive) return flattenRecursive(coll, 0); // flatten everything
        return flattenRecursive(coll, 0, 2); // flatten one level of lists in the input list if not recursive
    }

    private static List<Object> flattenRecursive(Object aObject, int aDepth, int aStopDepth) {
        List<Object> vResult = new ArrayList<Object>();

        if (aDepth == aStopDepth) { // always for a future arbitrary stopping point
            vResult.add(aObject);
        } else {
            if (aObject.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(aObject); i++) {
                    vResult.addAll(flattenRecursive(Array.get(aObject, i), aDepth + 1, aStopDepth));
                }
            } else if (aObject instanceof List) {
                for (Object vElement : (List<?>) aObject) {
                    vResult.addAll(flattenRecursive(vElement, aDepth + 1, aStopDepth));
                }
            } else {
                vResult.add(aObject);
            }
        }
        return vResult;
    }

    private static List<Object> flattenRecursive(Object aObject, int aDepth) {
        return flattenRecursive(aObject, aDepth, -1); // we only stop when all lists are flattened
    }

    @Deprecated
    @UserFunction
    @Description("apoc.coll.reverse(coll) - returns reversed list")
    public List<Object> reverse(@Name("coll") List<Object> coll) {
        if (coll == null || coll.isEmpty()) {
            return Collections.emptyList();
        } else if (coll.size() == 1) {
            return coll;
        }

        List<Object> reversed = new ArrayList<Object>(coll);
        Collections.reverse(reversed);

        return reversed;
    }

    @UserFunction("apoc.coll.sortMulti")
    @Description("apoc.coll.sortMulti(coll, ['^name','age'],[limit],[skip]) - sort list of maps by several sort fields (ascending with ^ prefix) and optionally applies limit and skip")
    public List<Map<String,Object>> sortMulti(@Name("coll") java.util.List<Map<String,Object>> coll,
                                              @Name(value="orderFields", defaultValue = "[]") java.util.List<String> orderFields,
                                              @Name(value="limit", defaultValue = "-1") long limit,
                                              @Name(value="skip", defaultValue = "0") long skip) {
        List<Map<String,Object>> result = new ArrayList<>(coll);

        if (orderFields != null && !orderFields.isEmpty()) {

            List<Pair<String, Boolean>> fields = orderFields.stream().map(v -> {
                boolean asc = v.charAt(0) == '^';
                return Pair.of(asc ? v.substring(1) : v, asc);
            }).collect(Collectors.toList());

            Comparator<Map<String, Comparable<Object>>> compare = (o1, o2) -> {
                int a = 0;
                for (Pair<String, Boolean> s : fields) {
                    if (a != 0) break;
                    String name = s.first();
                    Comparable<Object> v1 = o1.get(name);
                    Comparable<Object> v2 = o2.get(name);
                    if (v1 != v2) {
                        int cmp = (v1 == null) ? -1 : (v2 == null) ? 1 : v1.compareTo(v2);
                        a = (s.other()) ? cmp : -cmp;
                    }
                }
                return a;
            };

            Collections.sort((List<Map<String, Comparable<Object>>>) (List) result, compare);
        }
        if (skip > 0 && limit != -1L) return result.subList ((int)skip, (int)(skip + limit));
        if (skip > 0) return result.subList ((int)skip, result.size());
        if (limit != -1L) return result.subList (0, (int)limit);
        return result;
    }

    @UserFunction
    @Description("apoc.coll.combinations(coll, minSelect, maxSelect:minSelect) - Returns collection of all combinations of list elements of selection size between minSelect and maxSelect (default:minSelect), inclusive")
    public List<List<Object>> combinations(@Name("coll") List<Object> coll, @Name(value="minSelect") long minSelectIn, @Name(value="maxSelect",defaultValue = "-1") long maxSelectIn) {
        int minSelect = (int) minSelectIn;
        int maxSelect = (int) maxSelectIn;
        maxSelect = maxSelect == -1 ? minSelect : maxSelect;

        if (coll == null || coll.isEmpty() || minSelect < 1 || minSelect > coll.size() || minSelect > maxSelect || maxSelect > coll.size()) {
            return Collections.emptyList();
        }

        List<List<Object>> combinations = new ArrayList<>();

        for (int i = minSelect; i <= maxSelect; i++) {
            Iterator<int[]> itr = new Combinations(coll.size(), i).iterator();

            while (itr.hasNext()) {
                List<Object> entry = new ArrayList<>(i);
                int[] indexes = itr.next();
                if (indexes.length > 0) {
                    for (int index : indexes) {
                        entry.add(coll.get(index));
                    }
                    combinations.add(entry);
                }
            }
        }

        return combinations;
    }

    @UserFunction
    @Description("apoc.coll.different(values) - returns true if values are different")
    public boolean different(@Name("values") List<Object> values) {
		if (values == null) return false;
        return new HashSet(values).size() == values.size();
    }

    @UserFunction
    @Description("apoc.coll.dropDuplicateNeighbors(list) - remove duplicate consecutive objects in a list")
    public List<Object> dropDuplicateNeighbors(@Name("list") List<Object> list){
        if (list == null) return null;
	List<Object> newList = new ArrayList<>(list.size());

        Object last = null;
        for (Object element : list) {
            if (element == null && last != null || element != null && !element.equals(last)) {
                newList.add(element);
                last = element;
            }
        }

        return newList;
    }

    @UserFunction
    @Description("apoc.coll.fill(item, count) - returns a list with the given count of items")
    public List<Object> fill(@Name("item") String item, @Name("count") long count) {
        return Collections.nCopies((int) count, item);
    }

    @UserFunction
    @Description("apoc.coll.sortText(coll) sort on string based collections")
    public List<String> sortText(@Name("coll") List<String> coll, @Name(value = "conf", defaultValue = "{}") Map<String, Object> conf) {
        if (conf == null) conf = Collections.emptyMap();
        if (coll == null || coll.isEmpty()) return Collections.emptyList();
        List<String> sorted = new ArrayList<>(coll);
        String localeAsStr = conf.getOrDefault("locale", "").toString();
        final Locale locale = !localeAsStr.isBlank() ? Locale.forLanguageTag(localeAsStr) : null;
        Collator collator = locale != null ? Collator.getInstance(locale) : Collator.getInstance();
        Collections.sort(sorted, collator);
        return sorted;
    }

    @UserFunction("apoc.coll.pairWithOffset")
    @Description("apoc.coll.pairWithOffset(values, offset) - returns a list of pairs defined by the offset")
    public List<List<Object>> pairWithOffsetFn(@Name("values") List<Object> values, @Name("offset") long offset) {
        if (values == null) return null;
        BiFunction<List<Object>, Long, Object> extract = (list, index) -> index < list.size() && index >= 0 ? list.get(index.intValue()) : null;
        final int length = Double.valueOf(Math.ceil((double) values.size() / Math.abs(offset))).intValue();
        List<List<Object>> result = new ArrayList<>(length);
        for (long i = 0; i < values.size(); i++) {
            final List<Object> objects = asList(extract.apply(values, i), extract.apply(values, i + offset));
            result.add(objects);
        }
        return result;
    }

    @Procedure
    @Description("apoc.coll.pairWithOffset(values, offset) - returns a list of pairs defined by the offset")
    public Stream<ListResult> pairWithOffset(@Name("values") List<Object> values, @Name("offset") long offset) {
        return pairWithOffsetFn(values, offset).stream()
                .map(ListResult::new);
    }



}
