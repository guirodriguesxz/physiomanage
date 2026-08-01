package com.physiomanage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.service.CachedAvailability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Cache de disponibilidade (ver AvailabilityCache) via Redis. O valor é
 * serializado como CachedAvailability (tipo concreto, não List<Instant>
 * genérico) usando o ObjectMapper do próprio Spring Boot — já registra o
 * módulo JSR-310, então Instant serializa/desserializa sem precisar
 * habilitar "default typing" polimórfico do Jackson (que exigiria validar
 * uma lista de tipos permitidos por segurança).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                           ObjectMapper objectMapper,
                                           @Value("${app.cache.availability-ttl-seconds}") long ttlSeconds) {
        Jackson2JsonRedisSerializer<CachedAvailability> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, CachedAvailability.class);

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .build();
    }
}
