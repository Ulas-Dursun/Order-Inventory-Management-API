package com.ulasdursun.cartify.product;

import com.ulasdursun.cartify.exception.ProductNotFoundException;
import com.ulasdursun.cartify.product.dto.ProductRequest;
import com.ulasdursun.cartify.product.dto.ProductResponse;
import com.ulasdursun.cartify.product.dto.StockUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    public Page<ProductResponse> getAllActiveProducts(Pageable pageable) {
        return productRepository.findAllByActiveTrue(pageable)
                .map(ProductResponse::from);
    }

    public List<ProductResponse> getAllActiveProducts() {
        return productRepository.findAllByActiveTrue()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse getActiveProductById(UUID id) {
        return ProductResponse.from(findActiveOrThrow(id));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .build();
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        Product product = findActiveOrThrow(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse updateStock(UUID id, StockUpdateRequest request) {
        Product product = findActiveOrThrow(id);
        product.setStock(request.stock());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public void softDeleteProduct(UUID id) {
        Product product = findActiveOrThrow(id);
        product.setActive(false);
        productRepository.save(product);
    }

    public Product findActiveOrThrow(UUID id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ProductNotFoundException(
                        "Product not found with id: " + id
                ));
    }

    public Product findActiveOrThrowForUpdate(UUID id) {
        return productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(
                        "Product not found with id: " + id
                ));
    }
}