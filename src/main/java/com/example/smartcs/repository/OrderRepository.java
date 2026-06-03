package com.example.smartcs.repository;

import com.example.smartcs.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNo(String orderNo);
    List<Order> findByUserId(Long userId);
    List<Order> findByStatus(String status);
    List<Order> findByUserIdAndStatus(Long userId, String status);
    @Query("SELECT o FROM Order o WHERE o.totalAmount BETWEEN :min AND :max")
    List<Order> findByAmountRange(BigDecimal min, BigDecimal max);
}
