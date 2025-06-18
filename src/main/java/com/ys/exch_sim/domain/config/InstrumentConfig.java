package com.ys.exch_sim.domain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app")
@Data
public class InstrumentConfig {
    private Map<String, InstrumentDefinition> instruments;

    @Data
    public static class InstrumentDefinition {
        private String name;
        private long priceMultiplier;
        private long qtyMultiplier;
    }

    public boolean isValidSymbol(String symbol) {
        return instruments != null && instruments.containsKey(symbol.toUpperCase());
    }

    public InstrumentDefinition getInstrument(String symbol) {
        return instruments != null ? instruments.get(symbol.toUpperCase()) : null;
    }
}