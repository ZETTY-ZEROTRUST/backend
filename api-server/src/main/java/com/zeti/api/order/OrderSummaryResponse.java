package com.zeti.api.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long orderId,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime orderedAt) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getOrderId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getOrderedAt());
    }
}
