package com.zeti.api.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        Long userId,
        Long addressId,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime orderedAt,
        List<OrderItemResponse> items) {

    public static OrderDetailResponse of(Order order, List<OrderItem> items) {
        return new OrderDetailResponse(
                order.getOrderId(),
                order.getUserId(),
                order.getAddressId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getOrderedAt(),
                items.stream().map(OrderItemResponse::from).toList());
    }
}
