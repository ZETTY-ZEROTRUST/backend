package com.zeti.api.order.application.dto;

import com.zeti.api.order.domain.Order;
import com.zeti.api.order.domain.OrderItem;
import com.zeti.api.order.domain.OrderStatus;
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
