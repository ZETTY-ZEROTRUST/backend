package com.zeti.api.order.application.dto;

import com.zeti.api.order.domain.OrderItem;
import java.math.BigDecimal;

public record OrderItemResponse(
        Long itemId,
        String productName,
        Integer quantity,
        BigDecimal price) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getItemId(),
                item.getProductName(),
                item.getQuantity(),
                item.getPrice());
    }
}
