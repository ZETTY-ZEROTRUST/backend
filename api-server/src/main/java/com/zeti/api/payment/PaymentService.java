package com.zeti.api.payment;

import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    public List<PaymentResponse> listBalances(Long userId) {
        return paymentRepository.findByUserId(userId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    public List<PaymentHistoryResponse> listHistory(Long userId) {
        List<Long> paymentIds = paymentRepository.findByUserId(userId).stream()
                .map(Payment::getPaymentId)
                .toList();
        if (paymentIds.isEmpty()) {
            return Collections.emptyList();
        }
        return paymentHistoryRepository.findByPaymentIdInOrderByPaidAtDesc(paymentIds).stream()
                .map(PaymentHistoryResponse::from)
                .toList();
    }
}
