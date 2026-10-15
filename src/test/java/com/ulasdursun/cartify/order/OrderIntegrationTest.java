package com.ulasdursun.cartify.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulasdursun.cartify.auth.dto.AuthResponse;
import com.ulasdursun.cartify.auth.dto.LoginRequest;
import com.ulasdursun.cartify.order.dto.CreateOrderRequest;
import com.ulasdursun.cartify.order.dto.OrderItemRequest;
import com.ulasdursun.cartify.order.dto.UpdateOrderStatusRequest;
import com.ulasdursun.cartify.product.Product;
import com.ulasdursun.cartify.product.ProductRepository;
import com.ulasdursun.cartify.user.Role;
import com.ulasdursun.cartify.user.User;
import com.ulasdursun.cartify.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OrderIntegrationTest {

    private static final int INITIAL_STOCK = 10;
    private static final double UNIT_PRICE = 50.0;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cartifydb_test")
                    .withUsername("testuser")
                    .withPassword("testpass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String userToken;
    private String otherUserToken;
    private String adminToken;
    private Product product;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        createUser("user@test.com", "password123", Role.USER);
        createUser("other@test.com", "otherpass123", Role.USER);
        createUser("admin@test.com", "adminpass123", Role.ADMIN);

        userToken      = loginAndGetToken("user@test.com",  "password123");
        otherUserToken = loginAndGetToken("other@test.com", "otherpass123");
        adminToken     = loginAndGetToken("admin@test.com", "adminpass123");

        product = createProductViaApi(adminToken, "Test Product", UNIT_PRICE, INITIAL_STOCK);
    }

    // --- POST /api/orders ---

    @Test
    void postOrder_withSufficientStock_createsOrderWithPendingStatus() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), 3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(UNIT_PRICE))
                .andExpect(jsonPath("$.totalPrice").value(150));
    }

    @Test
    void postOrder_withSufficientStock_deductsStockInDatabase() throws Exception {
        int quantity = 3;

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), quantity))))
                .andExpect(status().isCreated());

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.getStock()).isEqualTo(INITIAL_STOCK - quantity);
    }

    @Test
    void postOrder_withSufficientStock_persistsOrderInDatabase() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), 2))))
                .andExpect(status().isCreated());

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.get(0).getTotalPrice())
                .isEqualByComparingTo(BigDecimal.valueOf(UNIT_PRICE * 2));
    }

    @Test
    void postOrder_withInsufficientStock_returns409() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), INITIAL_STOCK + 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void postOrder_withInsufficientStock_doesNotModifyStockOrCreateOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), INITIAL_STOCK + 1))))
                .andExpect(status().isConflict());

        Product unchanged = productRepository.findById(product.getId()).orElseThrow();
        assertThat(unchanged.getStock()).isEqualTo(INITIAL_STOCK);
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    void postOrder_withoutToken_returns403() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(product.getId(), 1))))
                .andExpect(status().isForbidden());
    }

    // --- GET /api/orders/{id} ---

    @Test
    void getOrderById_withOwner_returnsOrder() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getOrderById_withNonOwner_returns403() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOrderById_withAdmin_returnsOrder() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
    }

    // --- GET /api/orders ---

    @Test
    void getOrders_returnsOnlyAuthenticatedUsersOrders() throws Exception {
        createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getOrders_doesNotReturnOtherUsersOrders() throws Exception {
        createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- PATCH /api/orders/{id}/status ---

    @Test
    void patchOrderStatus_withAdmin_updatesStatusSuccessfully() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateOrderStatusRequest(OrderStatus.CONFIRMED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void patchOrderStatus_withRegularUser_returns403() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateOrderStatusRequest(OrderStatus.CONFIRMED))))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchOrderStatus_withInvalidTransition_returns422() throws Exception {
        String orderId = createOrderAndGetId(userToken, product.getId(), 1);

        patchStatus(orderId, OrderStatus.CONFIRMED);
        patchStatus(orderId, OrderStatus.SHIPPED);
        patchStatus(orderId, OrderStatus.DELIVERED);

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateOrderStatusRequest(OrderStatus.PENDING))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    // --- helpers ---

    private void createUser(String email, String password, Role role) {
        userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(role)
                .build());
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class
        ).token();
    }

    private Product createProductViaApi(String token, String name, double price, int stock)
            throws Exception {
        String body = """
                {"name":"%s","description":"test","price":%.2f,"stock":%d}
                """.formatted(name, price, stock);

        MvcResult result = mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        UUID id = UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asText()
        );
        return productRepository.findById(id).orElseThrow();
    }

    private String createOrderAndGetId(String token, UUID productId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderRequest(productId, quantity))))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private void patchStatus(String orderId, OrderStatus status) throws Exception {
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateOrderStatusRequest(status))))
                .andExpect(status().isOk());
    }

    private CreateOrderRequest orderRequest(UUID productId, int quantity) {
        return new CreateOrderRequest(List.of(new OrderItemRequest(productId, quantity)));
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}