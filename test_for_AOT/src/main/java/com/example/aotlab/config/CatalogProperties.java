package com.example.aotlab.config;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "catalog")
public record CatalogProperties(
        @NotBlank String currency,
        @Min(10) int preloadCount,
        @Min(1) long cacheTtlSeconds,
        @Min(1) int featuredRatio,
        @NotEmpty List<String> categories
) {
}
