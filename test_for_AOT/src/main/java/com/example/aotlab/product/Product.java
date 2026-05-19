package com.example.aotlab.product;

import java.math.BigDecimal;
import java.time.Instant;

public record Product(
        String id,
        String name,
        String category,
        BigDecimal price,
        int stock,
        boolean featured,
        Instant createdAt
) {
}
