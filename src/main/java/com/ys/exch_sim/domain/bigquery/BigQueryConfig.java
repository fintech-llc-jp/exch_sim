package com.ys.exch_sim.domain.bigquery;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.data-migration.bigquery-enabled", havingValue = "true", matchIfMissing = false)
public class BigQueryConfig {
    
    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;
    
    @Value("${spring.cloud.gcp.credentials.location:}")
    private String credentialsLocation;
    
    @Bean
    public BigQuery bigQuery() throws IOException {
        log.info("Initializing BigQuery client for project: {}", projectId);
        
        BigQueryOptions.Builder optionsBuilder = BigQueryOptions.newBuilder()
                .setProjectId(projectId);
        
        // 1. 環境変数 GOOGLE_APPLICATION_CREDENTIALS を最優先で確認
        String googleApplicationCredentials = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (googleApplicationCredentials != null && !googleApplicationCredentials.isEmpty()) {
            log.info("Using credentials from GOOGLE_APPLICATION_CREDENTIALS: {}", googleApplicationCredentials);
            GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(googleApplicationCredentials));
            optionsBuilder.setCredentials(credentials);
        }
        // 2. spring.cloud.gcp.credentials.location の設定を確認
        else if (!credentialsLocation.isEmpty()) {
            if (credentialsLocation.startsWith("classpath:")) {
                log.info("Using credentials from classpath: {}", credentialsLocation);
                // Spring will handle classpath resources automatically
            } else {
                log.info("Using credentials from file: {}", credentialsLocation);
                GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsLocation));
                optionsBuilder.setCredentials(credentials);
            }
        }
        // 3. デフォルト認証情報を使用
        else {
            log.info("Using default credentials (Application Default Credentials)");
            // BigQueryOptions will automatically use default credentials
        }
        
        BigQuery bigQuery = optionsBuilder.build().getService();
        
        log.info("BigQuery client initialized successfully");
        return bigQuery;
    }
}