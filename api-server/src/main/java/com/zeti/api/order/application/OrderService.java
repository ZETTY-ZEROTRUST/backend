package com.zeti.api.order.application;

import com.zeti.api.order.application.dto.OrderDetailResponse;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import com.zeti.api.order.domain.Order;
import com.zeti.api.order.domain.OrderItem;
import com.zeti.api.order.infrastructure.persistence.OrderItemRepository;
import com.zeti.api.order.infrastructure.persistence.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    public List<OrderSummaryResponse> listByUserId(Long userId) {
        return orderRepository.findByUserIdOrderByOrderedAtDesc(userId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    public OrderDetailResponse getDetail(Long userId, Long orderId) {
        Order order = orderRepository.findByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return OrderDetailResponse.of(order, items);
    }
}
