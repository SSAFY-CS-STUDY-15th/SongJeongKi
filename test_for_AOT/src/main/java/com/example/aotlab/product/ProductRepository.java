package com.example.aotlab.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

import com.example.aotlab.config.CatalogProperties;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private final ConcurrentMap<String, Product> products = new ConcurrentHashMap<>();

    public ProductRepository(CatalogProperties properties) {
        preloadProducts(properties);
    }

    public List<Product> findAll(String category, int limit) {
        return this.products.values().stream()
                .filter(product -> category == null || product.category().equalsIgnoreCase(category))
                .sorted(Comparator.comparing(Product::featured).reversed().thenComparing(Product::id))
                .limit(limit)
                .toList();
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(this.products.get(id));
    }

    public Product save(ProductCreateRequest request) {
        String id = "U-" + UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product(
                id,
                request.name(),
                request.category(),
                request.price(),
                request.stock(),
                false,
                Instant.now()
        );
        this.products.put(id, product);
        return product;
    }

    public int count() {
        return this.products.size();
    }

    private void preloadProducts(CatalogProperties properties) {
        List<String> categories = properties.categories();
        IntStream.rangeClosed(1, properties.preloadCount())
                .mapToObj(index -> createProduct(index, categories, properties.featuredRatio()))
                .forEach(product -> this.products.put(product.id(), product));
    }

    private Product createProduct(int index, List<String> categories, int featuredRatio) {
        String category = categories.get((index - 1) % categories.size());
        BigDecimal price = BigDecimal.valueOf(9000L + (long) index * 137L);
        return new Product(
                "P-%04d".formatted(index),
                category + "-item-" + index,
                category,
                price,
                10 + index % 90,
                index % featuredRatio == 0,
                Instant.EPOCH.plusSeconds(index)
        );
    }
}
