package apoc.redis;

import io.lettuce.core.ScriptOutputType;

import java.util.List;
import java.util.Map;

public interface IRedisConnection<T> extends AutoCloseable {
    // -- String
    T get(T key);
    T setGet(T key, T value);
    long append(T key, T value);
    long incrby(T key, long amount);

    // -- Hashes
    long hdel(T key, List<Object> fields);
    boolean hexists(T key, T field);
    T hget(T key, T field);
    long hincrby(T key, T field, long amount);
    boolean hset(T key, T field, T value);
    Map<String, Object> hgetall(T key);

    // -- Lists
    long push(T key, List<Object> values);
    T pop(T key);
    List<Object> lrange(T key, long start, long stop);

    // -- Sets
    long sadd(T key, List<Object> members);
    T spop(T key);
    long scard(T key);
    List<Object> smembers(T key);
    List<Object> sunion(List<Object> keys);

    // -- Sorted Sets
    long zadd(T key, Object... scoresAndMembers);
    long zcard(T key);
    List<Object> zrangebyscore(T source, long min, long max);
    long zrem(T source, List<Object> members);

    // -- Script
    T eval(String script, ScriptOutputType outputType, List<Object> keys, List<Object> values);

    // -- Key
    boolean copy(T source, T destination);
    long exists(List<Object> key);
    boolean pexpire(T key, long time, boolean isExpireAt);
    boolean persist(T key);
    long pttl(T key);

    // -- Server
    String info();
    String configSet(String parameter, String value);
    Map<String, Object> configGet(String parameter);
}
