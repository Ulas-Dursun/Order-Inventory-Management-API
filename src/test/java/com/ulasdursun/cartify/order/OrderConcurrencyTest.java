package com.ulasdursun.cartify.order;

import com.ulasdursun.cartify.exception.InsufficientStockException;
import com.ulasdursun.cartify.order.dto.CreateOrderRequest;
import com.ulasdursun.cartify.order.dto.OrderItemRequest;
import com.ulasdursun.cartify.product.Product;
import com.ulasdursun.cartify.product.ProductRepository;
import com.ulasdursun.cartify.user.Role;
import com.ulasdursun.cartify.user.User;
import com.ulasdursun.cartify.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private Product limitedProduct;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .email("concurrency-test@cartify.com")
                .password("hashed_pwd")
                .role(Role.USER)
                .build());

        // Product with only 2 items in stock
        limitedProduct = productRepository.save(Product.builder()
                .name("Limited Edition GPU")
                .description("High demand item")
                .price(new BigDecimal("799.99"))
                .stock(2)
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Pessimistic locking prevents overselling under high concurrency (10 threads competing for 2 items)")
    void concurrentOrders_withPessimisticLocking_preventsOverselling() throws InterruptedException {
        int threadCount = 10;
        int initialStock = 2;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    // Wait for all threads to be ready so they hit the DB concurrently
                    startLatch.await();

                    CreateOrderRequest request = new CreateOrderRequest(
                            List.of(new OrderItemRequest(limitedProduct.getId(), 1))
                    );

                    orderService.createOrder(request, testUser);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficientStockCount.incrementAndGet();
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait until all threads are spun up and waiting
        readyLatch.await(5, TimeUnit.SECONDS);

        // Fire all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to finish
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherErrorCount.get()).isZero();

        // Exactly initialStock orders must succeed
        assertThat(successCount.get()).isEqualTo(initialStock);

        // All other concurrent requests must fail with InsufficientStockException
        assertThat(insufficientStockCount.get()).isEqualTo(threadCount - initialStock);

        // Stock in database must be exactly 0, never negative
        Product updatedProduct = productRepository.findById(limitedProduct.getId()).orElseThrow();
        assertThat(updatedProduct.getStock()).isZero();

        // Exactly 2 orders must exist in the database
        assertThat(orderRepository.count()).isEqualTo(initialStock);
    }
}
