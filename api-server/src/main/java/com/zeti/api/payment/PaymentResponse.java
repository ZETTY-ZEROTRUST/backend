package com.zeti.api.payment;

import java.math.BigDecimal;

public record PaymentResponse(
        Long paymentId,
        PaymentMethod method,
        String maskedInfo,
        BigDecimal balance) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getMethod(),
                payment.getMaskedInfo(),
                payment.getBalance());
    }
}
