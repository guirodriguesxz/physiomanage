package com.physiomanage.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Limitador de taxa por janela fixa, contado no Redis: INCR seguido de
 * EXPIRE (só no primeiro INCR da janela), executado como um único script
 * Lua para não ter uma janela de corrida entre os dois comandos — se o
 * processo morresse entre eles, a chave ficaria sem TTL e o limite
 * travaria pra sempre.
 *
 * Janela fixa é uma simplificação conhecida (permite um pico de até ~2x
 * o limite bem na borda entre duas janelas, diferente de uma janela
 * deslizante); aceitável aqui porque isso é uma camada extra de defesa
 * contra brute-force/spam, não a única — a senha já é validada com
 * BCrypt independentemente disso.
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
                    "if tonumber(current) == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "return current",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryConsume(String key, int maxAttempts, int windowSeconds) {
        Long attempts = redisTemplate.execute(INCREMENT_AND_EXPIRE, List.of(key), String.valueOf(windowSeconds));
        return attempts != null && attempts <= maxAttempts;
    }
}
