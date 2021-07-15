package apoc.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;

abstract public class RedisConnection<T> implements IRedisConnection<T> {
    protected final RedisClient client;
    protected final RedisConfig conf;
    
    public RedisConnection(String uri, RedisConfig config) {
        this.conf = config;
        this.client = RedisClient.create(uri);
        this.client.setDefaultTimeout(conf.getTimeout());
        this.client.setOptions(ClientOptions.builder()
                .scriptCharset(conf.getScriptCharset())
                .autoReconnect(conf.isAutoReconnect())
                .build());
    }

    @Override
    public void close() {
        this.client.shutdown();
    }
}
