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
        private String type; // Cash or FX

        public boolean isCash() {
            return "Cash".equalsIgnoreCase(type);
        }

        public boolean isFX() {
            return "FX".equalsIgnoreCase(type);
        }

        /**
         * Get the minimum quantity that can be represented with this instrument's qtyMultiplier
         * @return minimum quantity (e.g., 0.001 for qtyMultiplier=1000)
         */
        public double getMinimumQuantity() {
            return 1.0 / qtyMultiplier;
        }

        /**
         * Normalize quantity to the minimum unit by truncating extra precision
         * For example, with qtyMultiplier=1000 (min unit = 0.001):
         *   0.0019 -> 0.001
         *   0.0025 -> 0.002
         * @param quantity the quantity to normalize
         * @return normalized quantity (truncated to minimum unit)
         */
        public double normalizeQuantity(double quantity) {
            if (quantity <= 0) {
                return 0.0;
            }
            // Convert to long representation and back to truncate extra precision
            long longQty = (long) (quantity * qtyMultiplier);
            return (double) longQty / qtyMultiplier;
        }

        /**
         * Validate if the quantity is positive after normalization
         * @param quantity the quantity to validate
         * @return true if quantity is valid (positive after normalization), false otherwise
         */
        public boolean isValidQuantity(double quantity) {
            return normalizeQuantity(quantity) > 0;
        }
    }

    public boolean isValidSymbol(String symbol) {
        return instruments != null && instruments.containsKey(symbol.toUpperCase());
    }

    public InstrumentDefinition getInstrument(String symbol) {
        return instruments != null ? instruments.get(symbol.toUpperCase()) : null;
    }
}