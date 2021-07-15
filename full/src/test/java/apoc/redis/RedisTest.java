package apoc.redis;

import apoc.util.TestUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.isRunningInCI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class RedisTest {
    private static final String PASSWORD = "SUPER_SECRET";
    private static final int REDIS_DEFAULT_PORT = 6379;
    
    private static int BEFORE_CONNECTION = 0;
    private static String URI;

    private static GenericContainer redis;

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();


    @BeforeClass
    public static void beforeClass() {
        assumeFalse(isRunningInCI());
        TestUtil.ignoreException(() -> {
            redis = new GenericContainer("redis:6.2.3")
                    .withCommand("redis-server --requirepass " + PASSWORD)
                    .withExposedPorts(REDIS_DEFAULT_PORT);
            redis.start();
        }, Exception.class);
        TestUtil.registerProcedure(db, Redis.class);
        assumeNotNull(redis);
        assumeTrue("Redis must be running", redis.isRunning());
        URI = String.format("redis://%s@%s:%s", PASSWORD, redis.getHost(), redis.getMappedPort(REDIS_DEFAULT_PORT));
        BEFORE_CONNECTION = getNumConnections();
    }

    @AfterClass
    public static void tearDown() {
        if (redis != null) {
            redis.stop();
        }
    }

    @After
    public void after() throws IOException, InterruptedException {
        assertEquals(BEFORE_CONNECTION, getNumConnections());
        redis.execInContainer("redis-cli", "-a", PASSWORD, "FLUSHALL");
    }
    
    @Parameterized.Parameters
    public static Collection<Object> data() {
        return List.of( RedisConfig.Codec.STRING.name(), RedisConfig.Codec.BYTE_ARRAY.name() );
    }

    @Parameterized.Parameter
    public String codec;

    
    @Test
    public void testStringsCommands() {
        Map<String, Object> config = map("codec", codec);
        Object key = getByCodec("myKey");
        TestUtil.testCall(db, "CALL apoc.redis.setGet($uri, $key, $myValue, $config)", 
                map("uri", URI, "key", key, "myValue", getByCodec("myValue"), "config", config),
                r -> assertNull(r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.setGet($uri, $key, $myNewValue, $config)", 
                map("uri", URI, "key", key, "myNewValue", getByCodec("myNewValue"), "config", config),
                r -> assertEquals("myValue", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.get($uri, $key, $config)", 
                map("uri", URI, "key", key,"config", config),
                r -> assertEquals("myNewValue", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.append($uri, $key, $2, $config)", 
                map("uri", URI, "key", key, "2", getByCodec("2"), "config", config),
                r -> {
                    assertEquals((long) "myNewValue2".length(), r.get("value"));
                });

        // incrby
        TestUtil.testCall(db, "CALL apoc.redis.setGet($uri, $key, $1, $config)", 
                map("uri", URI, "key", key, "1", getByCodec("1"), "config", config),
                r -> assertEquals("myNewValue2", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.incrby($uri, $key, 2, $config)", 
                map("uri", URI, "key", key, "2", getByCodec("2"), "config", config),
                r -> assertEquals(3L, r.get("value")));
    }

    @Test
    public void testListsCommands() {
        Map<String, Object> config = map("codec", this.codec);
        Object listKey = getByCodec("myListKey");
        TestUtil.testCall(db, "CALL apoc.redis.push($uri, $listKey, $values, $config)",
                map("uri", URI, "listKey", listKey, 
                        "values", getListByCodec(List.of("foo", "bar", "baz")),
                        "config", config),
                r -> assertEquals(3L, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.lrange($uri, $listKey, 0 , 10, $config)",
                map("uri", URI, "listKey", listKey, "config", config),
                r -> assertEquals(List.of("foo", "bar", "baz"), fromCodecList((List<Object>) r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.push($uri, $listKey, [$prefix1], $config)",
                map("uri", URI, "listKey", listKey,
                        "prefix1", getByCodec("prefix1"),
                        "config", map("codec", this.codec, "right", false)),
                r -> assertEquals(4L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.lrange($uri, $listKey, 0 , 10, $config)",
                map("uri", URI, "listKey", listKey, "config", config),
                r -> assertEquals(List.of("prefix1", "foo", "bar", "baz"), fromCodecList((List<Object>) r.get("value"))));
        
        TestUtil.testCall(db, "CALL apoc.redis.pop($uri, $listKey, $config)", 
                map("uri", URI, "listKey", listKey, "config", config),
                r -> assertEquals("baz", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.lrange($uri, $listKey, 0 , 10, $config)",
                map("uri", URI, "listKey", listKey, "config", config),
                r -> assertEquals(List.of("prefix1", "foo", "bar"), fromCodecList((List<Object>) r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.pop($uri, $listKey, $config)",
                map("uri", URI, "listKey", listKey, "config", map("codec", this.codec, "right", false)),
                r -> assertEquals("prefix1", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.lrange($uri, $myListKey, 0 , 10, $config)",
                map("uri", URI, "myListKey", listKey, "config", config),
                r -> assertEquals(List.of("foo", "bar"), fromCodecList((List<Object>) r.get("value"))));
    }

    @Test
    public void testSetsCommands() {
        Map<String, Object> config = map("codec", this.codec);
        Object key = getByCodec("mySetKey");
        TestUtil.testCall(db, "CALL apoc.redis.sadd($uri, $key, $members, $config)",
                map("uri", URI, "key", key, 
                        "members", getListByCodec(List.of("foo", "bar", "baz")), "config", config),
                r -> assertEquals(3L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.sadd($uri, $mySetKeyTwo, $members, $config)",
                map("uri", URI, "members", getListByCodec(List.of("alpha", "beta")),
                        "mySetKeyTwo", getByCodec("mySetKeyTwo"), "config", config),
                r -> assertEquals(2L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.sunion($uri, $keys, $config)",
                map("uri", URI, "keys", getListByCodec(List.of("mySetKey", "mySetKeyTwo")), "config", config),
                r -> assertEquals(Set.of("foo", "bar", "baz", "alpha", "beta"), new HashSet<>(fromCodecList((List<Object>) r.get("value")))));

        TestUtil.testCall(db, "CALL apoc.redis.scard($uri, $key, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(3L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.smembers($uri, $key, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(Set.of("foo", "bar", "baz"), new HashSet<>(fromCodecList((List<Object>) r.get("value")))));

        TestUtil.testCall(db, "CALL apoc.redis.spop($uri, $key, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertTrue(List.of("foo", "bar", "baz").contains(fromCodec(r.get("value")))));

        TestUtil.testCall(db, "CALL apoc.redis.scard($uri, $key, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(2L, r.get("value")));
    }

    @Test
    public void testSortedSetsCommands() {
        Map<String, Object> config = map("codec", this.codec);
        Object key = getByCodec("mySortedSetKey");
        TestUtil.testCall(db, "CALL apoc.redis.zadd($uri, $key, [0, $first, 100, $third, 1, $second], $config)",
                map("uri", URI, 
                        "first", getByCodec("first"), "second", getByCodec("second"), "third", getByCodec("third"),
                        "key", key, "config", config),
                r -> assertEquals(3L, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.zcard($uri, $key, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(3L, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.zrangebyscore($uri, $key, 0, 100, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(List.of("first", "second", "third"), fromCodecList((List<Object>) r.get("value"))));
        
        TestUtil.testCall(db, "CALL apoc.redis.zrem($uri, $key, $members, $config)",
                map("uri", URI, "members", getListByCodec(List.of("first", "second")), "key", key, "config", config),
                r -> assertEquals(2L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.zrangebyscore($uri, $key, 0, 100, $config)",
                map("uri", URI, "key", key, "config", config),
                r -> assertEquals(List.of("third"), fromCodecList((List<Object>) r.get("value"))));
    }

    @Test
    public void testHashesCommands() {
        Map<String, Object> config = map("codec", this.codec);
        Object key = getByCodec("mapKey");
        Object numberValue = getByCodec("number");
        TestUtil.testCall(db, "CALL apoc.redis.hset($uri, $key, $field, $value, $config)",
                map("uri", URI, "key", key, "field", numberValue, "value", getByCodec("1"), "config", config),
                r -> assertEquals(true, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.hset($uri, $key, $field, $value, $config)",
                map("uri", URI, "key", key, "field", getByCodec("alpha"), "value", getByCodec("beta"), "config", config),
                r -> assertEquals(true, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.hset($uri, $key, $field, $value, $config)",
                map("uri", URI, "key", key, "field", getByCodec("gamma"), "value", getByCodec("delta"), "config", config),
                r -> assertEquals(true, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.hdel($uri, $mapKey, $fields, $config)",
                map("uri", URI, "fields", getListByCodec(List.of("alpha", "gamma")), "mapKey", key, "config", config),
                r -> assertEquals(2L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.hexists($uri, $mapKey, $value, $config)",
                map("uri", URI, "value", numberValue, "mapKey", key, "config", config),
                r -> assertEquals(true, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.hget($uri, $mapKey, $value, $config)",
                map("uri", URI, "value", numberValue, "mapKey", key, "config", config),
                r -> assertEquals("1", fromCodec(r.get("value"))));

        TestUtil.testCall(db, "CALL apoc.redis.hincrby($uri, $mapKey, $value, 3, $config)",
                map("uri", URI, "mapKey", key, "value", numberValue, "config", config),
                r -> assertEquals(4L, r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.hgetall($uri, $mapKey, $config)",
                map("uri", URI, "mapKey", key, "config", config),
                r -> {
                    Map<String, String> actualMap = ((Map<String, Object>) r.get("value")).entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, i -> fromCodec(i.getValue())));
                    assertEquals(map("number", "4"), actualMap);
                });
    }

    @Test
    public void testEvalCommand() {
        Object keyEval = getByCodec("testEval");
        TestUtil.testCall(db, "CALL apoc.redis.setGet($uri, $keyEval, $valueEval, $config)",
                map("uri", URI, "keyEval", keyEval, "valueEval", getByCodec("valueEval"), "config", map("codec", codec)), 
                r -> assertNull(r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.eval($uri, 'return redis.call(\"get\", KEYS[1])', 'VALUE', [$keyEval], [$keyName], $config)", 
                map("uri", URI, "keyEval", keyEval, "keyName", getByCodec("key:name"), "config", map("codec", codec)),
                r -> assertEquals("valueEval", fromCodec(r.get("value"))));
    }

    @Test
    public void testKeysCommand() {
        Map<String, Object> config = map("codec", this.codec);
        Object from = getByCodec("from");
        Object to = getByCodec("to");
        TestUtil.testCall(db, "CALL apoc.redis.setGet($uri, $from, $one, $config)",
                map("uri", URI, "from", from, "one", getByCodec("one"), "config", config),
                r -> assertNull(r.get("value")));

        TestUtil.testCall(db, "CALL apoc.redis.copy($uri, $from, $to, $config)",
                map("uri", URI, "from", from, "to", to, "config", config),
                r -> assertEquals(true, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.get($uri, $to, $config)",
                map("uri", URI, "to", to, "config", config),
                r -> assertEquals("one", fromCodec(r.get("value"))));
        
        TestUtil.testCall(db, "CALL apoc.redis.exists($uri, [$to], $config)",
                map("uri", URI, "to", to, "config", config),
                r -> assertEquals(1L, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.pexpire($uri, $to, 9999, false, $config)",
                map("uri", URI, "to", to, "config", config),
                r -> assertEquals(true, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.pttl($uri, $to, $config)",
                map("uri", URI, "to", to, "config", config),
                r -> {
                    final long value = (long) r.get("value");
                    assertTrue( value <= 9999L && value > 0L);
                });

        TestUtil.testCall(db, "CALL apoc.redis.persist($uri, $to, $config)",
                map("uri", URI, "to", to, "config", config),
                r -> assertEquals(true, r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.pttl($uri, $to, $config)",
                map("uri", URI, "to", to, "config", config),
                r -> assertEquals(-1L, (long) r.get("value")));
    }

    @Test
    public void testServersCommand() {
        TestUtil.testCall(db, "CALL apoc.redis.info($uri, $config)",
                map("uri", URI, "config", map("codec", codec)),
                r -> assertTrue(((String) r.get("value")).contains("redis_version:6.2.3")));

        final String keyConfig = "slowlog-max-len";
        TestUtil.testCall(db, "CALL apoc.redis.configGet($uri, $keyConfig, $config)",
                map("uri", URI, "config", map("codec", codec), "keyConfig", keyConfig),
                r -> assertEquals(map(keyConfig, "128"), r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.configSet($uri, $keyConfig, '64', $config)",
                map("uri", URI, "config", map("codec", codec), "keyConfig", keyConfig),
                r -> assertEquals("OK", r.get("value")));
        
        TestUtil.testCall(db, "CALL apoc.redis.configGet($uri, $keyConfig, $config)",
                map("uri", URI, "config", map("codec", codec), "keyConfig", keyConfig),
                r -> assertEquals(map(keyConfig, "64"), r.get("value")));
        
        // to reset default value
        TestUtil.testCall(db, "CALL apoc.redis.configSet($uri, $keyConfig, '128')",
                map("uri", URI, "keyConfig", keyConfig),
                r -> assertEquals("OK", r.get("value")));
    }

    private static int getNumConnections() {
        try {
            return StringUtils.countMatches(redis.execInContainer("redis-cli", "CLIENT", "LIST").getStdout(),
                    System.lineSeparator());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String fromCodec(Object value) {
        switch (codec) {
            case "BYTE_ARRAY":
                return new String((byte[]) value);
            default:
                return (String) value;
        }
    }

    private Object getByCodec(String value) {
        switch (codec) {
            case "BYTE_ARRAY":
                return value.getBytes();
            default:
                return value;
        }
    }

    private List<Object> getListByCodec(List<String> values) {
        return values.stream().map(this::getByCodec).collect(Collectors.toList());
    }

    private List<String> fromCodecList(List<Object> value) {
        return value.stream().map(this::fromCodec).collect(Collectors.toList());
    }
}
