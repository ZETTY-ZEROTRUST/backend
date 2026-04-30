package com.zeti.api.order;

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
