package com.example.aotlab.product;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository repository;
    private final PriceLabelFormatter priceLabelFormatter;

    public ProductService(ProductRepository repository, PriceLabelFormatter priceLabelFormatter) {
        this.repository = repository;
        this.priceLabelFormatter = priceLabelFormatter;
    }

    @Cacheable(cacheNames = "productSearch", key = "#category + ':' + #limit")
    public List<ProductResponse> findAll(String category, int limit) {
        return this.repository.findAll(category, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(cacheNames = "productById", key = "#id")
    public ProductResponse findById(String id) {
        return this.repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @CacheEvict(cacheNames = {"productById", "productSearch"}, allEntries = true)
    public ProductResponse create(ProductCreateRequest request) {
        return toResponse(this.repository.save(request));
    }

    public int count() {
        return this.repository.count();
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.id(),
                product.name(),
                product.category(),
                product.price(),
                this.priceLabelFormatter.format(product.price()),
                product.stock(),
                product.featured(),
                product.createdAt()
        );
    }
}
