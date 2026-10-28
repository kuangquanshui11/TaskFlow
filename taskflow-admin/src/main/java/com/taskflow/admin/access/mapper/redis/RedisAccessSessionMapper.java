package com.taskflow.admin.access.mapper.redis;

import java.time.Duration;
import java.util.List;
import com.taskflow.admin.access.mapper.AccessSessionMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisAccessSessionMapper implements AccessSessionMapper {
    private static final DefaultRedisScript<Long> CREATE = script("create");
    private static final DefaultRedisScript<Long> CHECK = script("check");
    private static final DefaultRedisScript<Long> ROTATE = script("rotate");
    private static final DefaultRedisScript<Long> REVOKE = script("revoke");
    private final StringRedisTemplate redis;
    public RedisAccessSessionMapper(StringRedisTemplate redis) { this.redis = redis; }
    private static DefaultRedisScript<Long> script(String action) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("access/session-" + action + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
    @Override public boolean create(String key, String token, long authenticatedAt, Duration idleTimeout, Duration grace, boolean allowNew) {
        return Long.valueOf(1).equals(redis.execute(CREATE, List.of(key), token, String.valueOf(idleTimeout.toMillis()),
                String.valueOf(authenticatedAt), String.valueOf(grace.toMillis()), allowNew ? "1" : "0"));
    }
    @Override public boolean validateAndTouch(String key, String token, Duration idleTimeout) {
        return Long.valueOf(1).equals(redis.execute(CHECK, List.of(key), token, String.valueOf(idleTimeout.toMillis())));
    }
    @Override public boolean rotate(String key, String previousToken, String token, Duration idleTimeout, Duration grace) {
        return Long.valueOf(1).equals(redis.execute(ROTATE, List.of(key), previousToken, token,
                String.valueOf(idleTimeout.toMillis()), String.valueOf(grace.toMillis())));
    }
    @Override public void revoke(String key, Duration idleTimeout) {
        redis.execute(REVOKE, List.of(key), String.valueOf(idleTimeout.toMillis()));
    }
}
