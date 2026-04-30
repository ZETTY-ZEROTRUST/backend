package com.zeti.api.order;

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

    // 정상 엔드포인트: JWT sub 기반 본인 주문 목록
    @GetMapping
    public List<OrderSummaryResponse> getMyOrders(@AuthenticationPrincipal Long userId) {
        return orderService.listByUserId(userId);
    }

    // 의도된 IDOR: path param userId vs JWT sub 일치 검증 없음 (ZETTY 시연 자산)
    @GetMapping("/{userId}")
    public List<OrderSummaryResponse> getOrdersByUser(@PathVariable Long userId) {
        return orderService.listByUserId(userId);
    }

    // 주문 상세: 인가 검증 없음 (orderId만 알면 조회 가능, 잠재적 IDOR)
    @GetMapping("/{orderId}/detail")
    public OrderDetailResponse getOrderDetail(@PathVariable Long orderId) {
        return orderService.getDetail(orderId);
    }
}
