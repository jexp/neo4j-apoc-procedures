package apoc.redis;

import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class StringRedisConnection extends RedisConnection<String> {
    
    private final RedisCommands<String, String> commands;

    public StringRedisConnection(String uri, RedisConfig config) {
        super(uri, config);
        
        StatefulRedisConnection<String, String> connection = client.connect(new StringCodec(conf.getCharset()));
        this.commands = connection.sync();
    }

    // -- String
    @Override
    public String get(String key) {
        return this.commands.get(key);
    }
    
    @Override
    public String setGet(String key, String value) {
        return this.commands.setGet(key, value);
    }

    @Override
    public long append(String key, String value) {
        return this.commands.append(key, value);
    }

    @Override
    public long incrby(String key, long amount) {
        return this.commands.incrby(key, amount);
    }
    
    // -- Hashes
    @Override
    public long hdel(String key, List<Object> fields) {
        return this.commands.hdel(key, toStringArray(fields));
    }

    @Override
    public boolean hexists(String key, String field) {
        return this.commands.hexists(key, field);
    }

    @Override
    public String hget(String key, String field) {
        return this.commands.hget(key, field);
    }

    @Override
    public long hincrby(String key, String field, long amount) {
        return this.commands.hincrby(key, field, amount);
    }

    @Override
    public boolean hset(String key, String field, String value) {
        return this.commands.hset(key, field, value);
    }

    @Override
    public Map<String, Object> hgetall(String key) {
        return Collections.unmodifiableMap(this.commands.hgetall(key));
    }

    // -- Lists
    @Override
    public long push(String key, List<Object> values) {
        return this.conf.isRight() 
                ? this.commands.rpush(key, toStringArray(values)) 
                : this.commands.lpush(key, toStringArray(values));
    }

    @Override
    public String pop(String key) {
        return this.conf.isRight()
                ? this.commands.rpop(key)
                : this.commands.lpop(key);
    }

    @Override
    public List<Object> lrange(String key, long start, long stop) {
        return new ArrayList<>(this.commands.lrange(key, start, stop));
    }

    // -- Sets
    @Override
    public long sadd(String key, List<Object> members) {
        return this.commands.sadd(key, toStringArray(members));
    }

    @Override
    public String spop(String key) {
        return this.commands.spop(key);
    }

    @Override
    public long scard(String key) {
        return this.commands.scard(key);
    }

    @Override
    public List<Object> smembers(String key) {
        return new ArrayList<>(this.commands.smembers(key));
    }

    @Override
    public List<Object> sunion(List<Object> keys) {
        return new ArrayList<>(this.commands.sunion(toStringArray(keys)));
    }

    // -- Sorted Sets
    @Override
    public long zadd(String key, Object... scoresAndMembers) {
        return this.commands.zadd(key, scoresAndMembers);
    }

    @Override
    public long zcard(String key) {
        return this.commands.zcard(key);
    }

    @Override
    public List<Object> zrangebyscore(String source, long min, long max) {
        return new ArrayList<>(this.commands.zrangebyscore(source, Range.create(min, max)));
    }

    @Override
    public long zrem(String source, List<Object> members) {
        return this.commands.zrem(source, toStringArray(members));
    }
    
    // -- Script
    @Override
    public String eval(String script, ScriptOutputType outputType, List<Object> keys, List<Object> values) {
        return this.commands.eval(script, outputType, toStringArray(keys), toStringArray(values));
    }

    // -- Key
    @Override
    public boolean copy(String source, String destination) {
        return this.commands.copy(source, destination);
    }

    @Override
    public long exists(List<Object> key) {
        return this.commands.exists(toStringArray(key));
    }

    @Override
    public boolean pexpire(String key, long time, boolean isExpireAt) {
        return isExpireAt
                ? this.commands.pexpireat(key, time)
                : this.commands.pexpire(key, time);
    }

    @Override
    public boolean persist(String key) {
        return this.commands.persist(key);
    }

    @Override
    public long pttl(String key) {
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

    private String[] toStringArray(List<Object> fields) {
        return fields.stream().map(String.class::cast).toArray(String[]::new);
    }
}
