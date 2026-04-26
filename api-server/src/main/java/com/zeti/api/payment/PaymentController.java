package com.zeti.api.payment;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // JWT sub 기반 본인 결제수단별 잔액 목록
    @GetMapping("/balance")
    public List<PaymentResponse> getBalances(@AuthenticationPrincipal Long userId) {
        return paymentService.listBalances(userId);
    }

    // JWT sub 기반 본인 결제 내역 (모든 결제수단 합산, 최신순)
    @GetMapping("/history")
    public List<PaymentHistoryResponse> getHistory(@AuthenticationPrincipal Long userId) {
        return paymentService.listHistory(userId);
    }
}
