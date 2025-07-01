package com.ys.exch_sim.domain.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        // Set cache names
        cacheManager.setCacheNames(java.util.Arrays.asList("volumeCache"));
        // Cache entries will be automatically evicted after some time due to concurrent map nature
        return cacheManager;
    }
}