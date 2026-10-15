package com.ulasdursun.cartify.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("""
        SELECT o FROM Order o
        LEFT JOIN FETCH o.items i
        LEFT JOIN FETCH i.product
        LEFT JOIN FETCH o.user
        WHERE o.id = :orderId
    """)
    Optional<Order> findDetailedById(@Param("orderId") UUID orderId);

    @Query("""
        SELECT DISTINCT o FROM Order o
        LEFT JOIN FETCH o.items i
        LEFT JOIN FETCH i.product
        WHERE o.user.id = :userId
    """)
    List<Order> findAllDetailedByUserId(@Param("userId") UUID userId);

    @Query(value = """
        SELECT o FROM Order o
        LEFT JOIN FETCH o.user
        WHERE o.user.id = :userId
    """, countQuery = "SELECT count(o) FROM Order o WHERE o.user.id = :userId")
    Page<Order> findAllByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
    SELECT DISTINCT o FROM Order o
    LEFT JOIN FETCH o.items i
    LEFT JOIN FETCH i.product
    LEFT JOIN FETCH o.user
""")
    List<Order> findAllDetailed();

    @Query(value = """
        SELECT o FROM Order o
        LEFT JOIN FETCH o.user
    """, countQuery = "SELECT count(o) FROM Order o")
    Page<Order> findAllOrders(Pageable pageable);
}
