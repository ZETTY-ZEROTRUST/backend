package com.zeti.api.order.application.dto;

import com.zeti.api.order.domain.Order;
import com.zeti.api.order.domain.OrderStatus;
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
