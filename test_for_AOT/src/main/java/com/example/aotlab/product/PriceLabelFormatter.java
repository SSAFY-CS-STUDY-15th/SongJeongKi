package com.example.aotlab.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;

public final class PriceLabelFormatter {

    private final String currency;
    private final String template;

    public PriceLabelFormatter(String currency, String template) {
        this.currency = currency;
        this.template = template;
    }

    public String format(BigDecimal price) {
        BigDecimal normalized = price.setScale(0, RoundingMode.HALF_UP);
        Map<String, String> values = Map.of(
                "currency", this.currency,
                "price", normalized.toPlainString()
        );
        return StringSubstitutor.replace(this.template, values);
    }
}
