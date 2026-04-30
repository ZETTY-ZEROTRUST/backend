package com.zeti.api.order;

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

    public OrderDetailResponse getDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return OrderDetailResponse.of(order, items);
    }
}
