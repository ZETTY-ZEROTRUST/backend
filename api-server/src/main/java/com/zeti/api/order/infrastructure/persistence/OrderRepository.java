package com.zeti.api.order.infrastructure.persistence;

import com.zeti.api.order.domain.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 마이페이지 요약: 최근 N건만 DB LIMIT으로 가져온다(전량 fetch 후 메모리 컷 제거).
    List<Order> findTop5ByUserIdOrderByOrderedAtDesc(Long userId);

    // 주문 목록: 페이지네이션. idx_orders_user_ordered(user_id, ordered_at DESC) 사용.
    Page<Order> findByUserIdOrderByOrderedAtDesc(Long userId, Pageable pageable);

    Optional<Order> findByOrderIdAndUserId(Long orderId, Long userId);
}
