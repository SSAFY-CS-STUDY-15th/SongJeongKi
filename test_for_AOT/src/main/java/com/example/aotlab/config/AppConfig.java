package com.example.aotlab.config;

import java.time.Duration;

import com.example.aotlab.product.PriceLabelFormatter;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableCaching
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Caffeine<Object, Object> productCacheBuilder(CatalogProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(100, properties.preloadCount()))
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .recordStats();
    }

    @Bean
    CacheManager cacheManager(Caffeine<Object, Object> productCacheBuilder) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("productById", "productSearch");
        cacheManager.setCaffeine(productCacheBuilder);
        return cacheManager;
    }

    @Bean
    PriceLabelFormatter priceLabelFormatter(CatalogProperties properties) {
        return new PriceLabelFormatter(properties.currency(), "${currency} ${price}");
    }
}
