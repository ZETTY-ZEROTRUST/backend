package com.zeti.api.payment.infrastructure.persistence;

import com.zeti.api.payment.domain.PaymentHistory;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    List<PaymentHistory> findByPaymentIdInOrderByPaidAtDesc(Collection<Long> paymentIds);
}
