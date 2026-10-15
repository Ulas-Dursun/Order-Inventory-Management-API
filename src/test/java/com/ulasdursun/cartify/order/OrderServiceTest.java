package com.ulasdursun.cartify.order;

import com.ulasdursun.cartify.exception.InsufficientStockException;
import com.ulasdursun.cartify.exception.InvalidOrderStatusTransitionException;
import com.ulasdursun.cartify.order.dto.CreateOrderRequest;
import com.ulasdursun.cartify.order.dto.OrderItemRequest;
import com.ulasdursun.cartify.order.dto.OrderResponse;
import com.ulasdursun.cartify.order.dto.UpdateOrderStatusRequest;
import com.ulasdursun.cartify.product.Product;
import com.ulasdursun.cartify.product.ProductService;
import com.ulasdursun.cartify.user.Role;
import com.ulasdursun.cartify.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final int INITIAL_STOCK = 10;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("25.00");

    @Mock private OrderRepository orderRepository;
    @Mock private ProductService productService;

    @InjectMocks private OrderService orderService;

    private User owner;
    private User otherUser;
    private User admin;
    private Product product;
    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();

        owner = User.builder()
                .id(UUID.randomUUID())
                .email("owner@test.com")
                .password("hashed")
                .role(Role.USER)
                .build();

        otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@test.com")
                .password("hashed")
                .role(Role.USER)
                .build();

        admin = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .password("hashed")
                .role(Role.ADMIN)
                .build();

        product = Product.builder()
                .id(productId)
                .name("Test Product")
                .price(UNIT_PRICE)
                .stock(INITIAL_STOCK)
                .active(true)
                .build();
    }

    @Test
    void createOrder_withSufficientStock_setsStatusToPending() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(productId, 3))
        );

        when(productService.findActiveOrThrowForUpdate(productId)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.createOrder(request, owner);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void createOrder_withValidOrder_deductsStockFromProduct() {
        int quantity = 4;
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(productId, quantity))
        );

        when(productService.findActiveOrThrowForUpdate(productId)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.createOrder(request, owner);

        assertThat(product.getStock()).isEqualTo(INITIAL_STOCK - quantity);
    }

    @Test
    void createOrder_calculatesTotalPriceFromUnitPriceSnapshot() {
        int quantity = 3;
        BigDecimal expectedTotal = UNIT_PRICE.multiply(BigDecimal.valueOf(quantity));

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(productId, quantity))
        );

        when(productService.findActiveOrThrowForUpdate(productId)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.createOrder(request, owner);

        assertThat(response.totalPrice()).isEqualByComparingTo(expectedTotal);
    }

    @Test
    void createOrder_withInsufficientStock_throwsInsufficientStockException() {
        int excessiveQuantity = INITIAL_STOCK + 1;
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(productId, excessiveQuantity))
        );

        when(productService.findActiveOrThrowForUpdate(productId)).thenReturn(product);

        assertThatThrownBy(() -> orderService.createOrder(request, owner))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Test Product");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void updateOrderStatus_withValidTransition_updatesStatus() {
        Order order = buildOrder(OrderStatus.PENDING, owner);

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.updateOrderStatus(
                order.getId(), new UpdateOrderStatusRequest(OrderStatus.CONFIRMED)
        );

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void updateOrderStatus_toCancelled_restoresStockUsingPessimisticLock() {
        Order order = buildOrder(OrderStatus.PENDING, owner);
        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID())
                .order(order)
                .product(product)
                .quantity(3)
                .unitPrice(UNIT_PRICE)
                .build();
        order.setItems(new ArrayList<>(List.of(item)));

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));
        when(productService.findActiveOrThrowForUpdate(productId)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.updateOrderStatus(
                order.getId(), new UpdateOrderStatusRequest(OrderStatus.CANCELLED)
        );

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getStock()).isEqualTo(INITIAL_STOCK + 3);
        verify(productService).findActiveOrThrowForUpdate(productId);
    }

    @Test
    void createOrder_withMultipleItems_locksProductsInAscendingIdOrderToPreventDeadlock() {
        UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Product productA = Product.builder().id(idA).name("A").price(BigDecimal.TEN).stock(5).active(true).build();
        Product productB = Product.builder().id(idB).name("B").price(BigDecimal.TEN).stock(5).active(true).build();

        // Client sends idB first, then idA
        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(idB, 1),
                new OrderItemRequest(idA, 1)
        ));

        when(productService.findActiveOrThrowForUpdate(idA)).thenReturn(productA);
        when(productService.findActiveOrThrowForUpdate(idB)).thenReturn(productB);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.createOrder(request, owner);

        // Verify locks are acquired strictly in sorted order (idA then idB)
        org.mockito.InOrder inOrder = inOrder(productService);
        inOrder.verify(productService).findActiveOrThrowForUpdate(idA);
        inOrder.verify(productService).findActiveOrThrowForUpdate(idB);
    }

    @Test
    void updateOrderStatus_fromTerminalState_throwsInvalidOrderStatusTransitionException() {
        Order order = buildOrder(OrderStatus.DELIVERED, owner);

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderStatus(
                order.getId(), new UpdateOrderStatusRequest(OrderStatus.PENDING)
        ))
                .isInstanceOf(InvalidOrderStatusTransitionException.class)
                .hasMessageContaining("DELIVERED");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void getOrderById_withOwner_returnsOrder() {
        Order order = buildOrder(OrderStatus.PENDING, owner);

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById(order.getId(), owner);

        assertThat(response.userId()).isEqualTo(owner.getId());
    }

    @Test
    void getOrderById_withNonOwner_throwsAccessDeniedException() {
        Order order = buildOrder(OrderStatus.PENDING, otherUser);

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrderById(order.getId(), owner))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getOrderById_withAdmin_canAccessAnyOrder() {
        Order order = buildOrder(OrderStatus.PENDING, otherUser);

        when(orderRepository.findDetailedById(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById(order.getId(), admin);

        assertThat(response.userId()).isEqualTo(otherUser.getId());
    }

    private Order buildOrder(OrderStatus status, User user) {
        return Order.builder()
                .id(UUID.randomUUID())
                .user(user)
                .status(status)
                .totalPrice(new BigDecimal("50.00"))
                .build();
    }
}