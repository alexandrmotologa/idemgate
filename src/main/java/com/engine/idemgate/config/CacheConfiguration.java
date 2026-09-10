package com.engine.idemgate.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.engine.idemgate.model.IdempotencyRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfiguration {

    @Bean
    public Cache<String, IdempotencyRecord> idempotencyL1Cache(IdemGateProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofSeconds(properties.getIdempotency().getRecordTtlSeconds()))
                .recordStats()
                .build();
    }
}
