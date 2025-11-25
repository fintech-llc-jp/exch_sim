package com.ys.exch_sim.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * BigQueryモードの時のみ、JPA/DataSourceの自動設定を除外するための設定クラス
 * 
 * 注意: このアプローチは動作しない可能性があります。
 * 代わりに、application.propertiesでspring.autoconfigure.excludeを使用してください。
 */
@Configuration
@ConditionalOnProperty(
    name = "app.database.type",
    havingValue = "bigquery",
    matchIfMissing = false)
public class BigQueryJpaExclusionConfig {
  // このクラスは実際には使用されません。
  // application.propertiesでspring.autoconfigure.excludeを設定する必要があります。
}

