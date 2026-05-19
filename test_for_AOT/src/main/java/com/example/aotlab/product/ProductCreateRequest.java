package com.example.aotlab.product;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ProductCreateRequest(
        @NotBlank String name,
        @NotBlank String category,
        @Positive BigDecimal price,
        @Min(0) int stock
) {
}
