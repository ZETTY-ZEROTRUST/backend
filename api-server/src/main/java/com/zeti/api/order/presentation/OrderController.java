package com.zeti.api.order.presentation;

import com.zeti.api.order.application.OrderService;
import com.zeti.api.order.application.dto.OrderDetailResponse;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public List<OrderSummaryResponse> getMyOrders(@AuthenticationPrincipal Long userId) {
        return orderService.listByUserId(userId);
    }

    @GetMapping("/{orderId}/detail")
    public OrderDetailResponse getOrderDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId) {
        return orderService.getDetail(userId, orderId);
    }
}
