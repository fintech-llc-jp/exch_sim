package com.ys.exch_sim.domain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * データベースサービスの設定
 * app.database.typeプロパティに基づいて適切な実装が自動的に選択される
 * （PostgreSQLDatabaseService）
 */
@Slf4j
@Configuration
public class DatabaseConfig {

    @Value("${app.database.type:postgresql}")
    private String databaseType;

    @PostConstruct
    public void logDatabaseType() {
        log.info("Database type configured: {}", databaseType);
        log.info("DatabaseService implementation will be selected based on app.database.type property");
    }
}

