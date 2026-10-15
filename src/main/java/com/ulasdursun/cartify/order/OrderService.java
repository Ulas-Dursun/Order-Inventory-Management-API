package com.ulasdursun.cartify.order;

import com.ulasdursun.cartify.exception.InsufficientStockException;
import com.ulasdursun.cartify.exception.InvalidOrderStatusTransitionException;
import com.ulasdursun.cartify.exception.OrderNotFoundException;
import com.ulasdursun.cartify.order.dto.CreateOrderRequest;
import com.ulasdursun.cartify.order.dto.OrderItemRequest;
import com.ulasdursun.cartify.order.dto.OrderResponse;
import com.ulasdursun.cartify.order.dto.UpdateOrderStatusRequest;
import com.ulasdursun.cartify.product.Product;
import com.ulasdursun.cartify.product.ProductService;
import com.ulasdursun.cartify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, User authenticatedUser) {
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;

        // Sort items by product ID to enforce deterministic lock ordering and prevent deadlocks under concurrent requests
        List<OrderItemRequest> sortedItemRequests = request.items().stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .toList();

        for (OrderItemRequest itemRequest : sortedItemRequests) {
            Product product = productService.findActiveOrThrowForUpdate(itemRequest.productId());

            if (product.getStock() < itemRequest.quantity()) {
                throw new InsufficientStockException(
                        "Insufficient stock for product '" + product.getName() + "'. " +
                                "Requested: " + itemRequest.quantity() + ", available: " + product.getStock()
                );
            }

            product.setStock(product.getStock() - itemRequest.quantity());

            OrderItem item = OrderItem.builder()
                    .product(product)
                    .quantity(itemRequest.quantity())
                    .unitPrice(product.getPrice())
                    .build();

            orderItems.add(item);
            totalPrice = totalPrice.add(
                    product.getPrice().multiply(BigDecimal.valueOf(itemRequest.quantity()))
            );
        }

        Order order = Order.builder()
                .user(authenticatedUser)
                .status(OrderStatus.PENDING)
                .totalPrice(totalPrice)
                .items(new ArrayList<>())
                .build();

        for (OrderItem item : orderItems) {
            item.setOrder(order);
            order.getItems().add(item);
        }

        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId, User authenticatedUser) {
        Order order = findDetailedOrThrow(orderId);
        enforceOwnership(order, authenticatedUser);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersForUser(User authenticatedUser, Pageable pageable) {
        return orderRepository.findAllByUserId(authenticatedUser.getId(), pageable)
                .map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(User authenticatedUser) {
        return orderRepository.findAllDetailedByUserId(authenticatedUser.getId())
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getAllOrders(Pageable pageable) {
        return orderRepository.findAllOrders(pageable)
                .map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAllDetailed()
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional
    public OrderResponse updateOrderStatus(UUID orderId, UpdateOrderStatusRequest request) {
        Order order = findDetailedOrThrow(orderId);

        if (!order.getStatus().canTransitionTo(request.status())) {
            throw new InvalidOrderStatusTransitionException(
                    "Cannot transition order from " + order.getStatus() + " to " + request.status()
            );
        }

        // Restore stock with pessimistic write lock if order is being cancelled
        if (request.status() == OrderStatus.CANCELLED) {
            // Sort items by product ID to avoid deadlock during concurrent cancellations
            List<OrderItem> sortedItems = order.getItems().stream()
                    .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                    .toList();

            for (OrderItem item : sortedItems) {
                Product product = productService.findActiveOrThrowForUpdate(item.getProduct().getId());
                product.setStock(product.getStock() + item.getQuantity());
            }
        }

        order.setStatus(request.status());
        return OrderResponse.from(orderRepository.save(order));
    }

    private Order findDetailedOrThrow(UUID orderId) {
        return orderRepository.findDetailedById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(
                        "Order not found with id: " + orderId
                ));
    }

    private void enforceOwnership(Order order, User authenticatedUser) {
        boolean isOwner = order.getUser().getId().equals(authenticatedUser.getId());
        boolean isAdmin = authenticatedUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("You do not have access to this order");
        }
    }
}