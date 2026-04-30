package com.zeti.api.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentHistoryResponse(
        Long historyId,
        Long paymentId,
        BigDecimal amount,
        String description,
        LocalDateTime paidAt) {

    public static PaymentHistoryResponse from(PaymentHistory history) {
        return new PaymentHistoryResponse(
                history.getHistoryId(),
                history.getPaymentId(),
                history.getAmount(),
                history.getDescription(),
                history.getPaidAt());
    }
}
