package com.zeti.api.order.presentation;

import com.zeti.api.order.application.OrderService;
import com.zeti.api.order.application.dto.OrderDetailResponse;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OrderService orderService;

    // 페이지 크기를 서버에서 상한한다. 클라이언트가 size를 키워 전량 조회로 되돌리지 못하게 한다.
    @GetMapping
    public Page<OrderSummaryResponse> getMyOrders(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return orderService.listByUserId(userId, pageable);
    }

    @GetMapping("/{orderId}/detail")
    public OrderDetailResponse getOrderDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId) {
        return orderService.getDetail(userId, orderId);
    }
}
