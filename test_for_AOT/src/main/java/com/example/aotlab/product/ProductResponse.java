package com.example.aotlab.product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        String id,
        String name,
        String category,
        BigDecimal price,
        String priceLabel,
        int stock,
        boolean featured,
        Instant createdAt
) {
}
