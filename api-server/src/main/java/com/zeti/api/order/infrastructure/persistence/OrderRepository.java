package com.zeti.api.order.infrastructure.persistence;

import com.zeti.api.order.domain.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserIdOrderByOrderedAtDesc(Long userId);

    Optional<Order> findByOrderIdAndUserId(Long orderId, Long userId);
}
