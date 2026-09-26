package com.zeti.api.order.application;

import com.zeti.api.order.application.dto.OrderDetailResponse;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import com.zeti.api.order.domain.Order;
import com.zeti.api.order.domain.OrderItem;
import com.zeti.api.order.infrastructure.persistence.OrderItemRepository;
import com.zeti.api.order.infrastructure.persistence.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    /** 마이페이지 요약용 최근 5건. 전량 조회하지 않는다. */
    public List<OrderSummaryResponse> recentByUserId(Long userId) {
        return orderRepository.findTop5ByUserIdOrderByOrderedAtDesc(userId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    /** 주문 목록. 페이지 단위로만 조회한다. */
    public Page<OrderSummaryResponse> listByUserId(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByOrderedAtDesc(userId, pageable)
                .map(OrderSummaryResponse::from);
    }

    public OrderDetailResponse getDetail(Long userId, Long orderId) {
        Order order = orderRepository.findByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return OrderDetailResponse.of(order, items);
    }
}
