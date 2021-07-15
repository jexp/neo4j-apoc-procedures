package apoc.redis;

import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ByteArrayRedisConnection extends RedisConnection<byte[]> {

    private final RedisCommands<byte[], byte[]> commands;

    public ByteArrayRedisConnection(String uri, RedisConfig config) {
        super(uri, config);

        StatefulRedisConnection<byte[], byte[]> connection = this.client.connect(new ByteArrayCodec());
        this.commands = connection.sync();
    }

    // -- String
    @Override
    public byte[] get(byte[] key) {
        return this.commands.get(key);
    }

    @Override
    public byte[] setGet(byte[] key, byte[] value) {
        return this.commands.setGet(key, value);
    }

    @Override
    public long append(byte[] key, byte[] value) {
        return this.commands.append(key, value);
    }

    // -- Hashes
    @Override
    public long incrby(byte[] key, long amount) {
        return this.commands.incrby(key, amount);
    }

    @Override
    public long hdel(byte[] key, List<Object> fields) {
        return this.commands.hdel(key, toBytesArray(fields));
    }

    @Override
    public boolean hexists(byte[] key, byte[] field) {
        return this.commands.hexists(key, field);
    }

    @Override
    public byte[] hget(byte[] key, byte[] field) {
        return this.commands.hget(key, field);
    }

    @Override
    public long hincrby(byte[] key, byte[] field, long amount) {
        return this.commands.hincrby(key, field, amount);
    }

    @Override
    public boolean hset(byte[] key, byte[] field, byte[] value) {
        return this.commands.hset(key, field, value);
    }

    @Override
    public Map<String, Object> hgetall(byte[] key) {
        return this.commands.hgetall(key)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(e -> new String(e.getKey()), Map.Entry::getValue));
    }

    // -- Lists
    @Override
    public long push(byte[] key, List<Object> values) {
        return this.conf.isRight()
                ? this.commands.rpush(key, toBytesArray(values))
                : this.commands.lpush(key, toBytesArray(values));
    }

    @Override
    public byte[] pop(byte[] key) {
        return this.conf.isRight()
                ? this.commands.rpop(key)
                : this.commands.lpop(key);
    }

    @Override
    public List<Object> lrange(byte[] key, long start, long stop) {
        return new ArrayList<>(this.commands.lrange(key, start, stop));
    }

    // -- Sets
    @Override
    public long sadd(byte[] key, List<Object> members) {
        return this.commands.sadd(key, toBytesArray(members));
    }

    @Override
    public byte[] spop(byte[] key) {
        return this.commands.spop(key);
    }

    @Override
    public long scard(byte[] key) {
        return this.commands.scard(key);
    }

    @Override
    public List<Object> smembers(byte[] key) {
        return new ArrayList<>(this.commands.smembers(key));
    }

    @Override
    public List<Object> sunion(List<Object> keys) {
        return new ArrayList<>(this.commands.sunion(toBytesArray(keys)));
    }

    // -- Sorted Sets
    @Override
    public long zadd(byte[] key, Object... scoresAndMembers) {
        return this.commands.zadd(key, scoresAndMembers);
    }

    @Override
    public long zcard(byte[] key) {
        return this.commands.zcard(key);
    }

    @Override
    public List<Object> zrangebyscore(byte[] source, long min, long max) {
        return new ArrayList<>(this.commands.zrangebyscore(source, Range.create(min, max)));
    }

    @Override
    public long zrem(byte[] source, List<Object> members) {
        return this.commands.zrem(source, toBytesArray(members));
    }
    
    // -- Script
    @Override
    public byte[] eval(String script, ScriptOutputType outputType, List<Object> keys, List<Object> values) {
        return this.commands.eval(script, outputType, toBytesArray(keys), toBytesArray(values));
    }

    // -- Key

    @Override
    public boolean copy(byte[] source, byte[] destination) {
        return this.commands.copy(source, destination);
    }

    @Override
    public long exists(List<Object> key) {
        return this.commands.exists(toBytesArray(key));
    }

    @Override
    public boolean pexpire(byte[] key, long time, boolean isExpireAt) {
        return isExpireAt
                ? this.commands.pexpireat(key, time)
                : this.commands.pexpire(key, time);
    }

    @Override
    public boolean persist(byte[] key) {
        return this.commands.persist(key);
    }

    @Override
    public long pttl(byte[] key) {
        return this.commands.pttl(key);
    }

    // -- Server
    @Override
    public String info() {
        return this.commands.info();
    }

    @Override
    public String configSet(String parameter, String value) {
        return this.commands.configSet(parameter, value);
    }

    @Override
    public Map<String, Object> configGet(String parameter) {
        return Collections.unmodifiableMap(this.commands.configGet(parameter));
    }
    
    private byte[][] toBytesArray(List<Object> fields) {
        return fields.stream().map(byte[].class::cast).toArray(byte[][]::new);
    }
}
