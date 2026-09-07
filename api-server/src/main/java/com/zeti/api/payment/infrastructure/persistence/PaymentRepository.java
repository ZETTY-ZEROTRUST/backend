package com.zeti.api.payment.infrastructure.persistence;

import com.zeti.api.payment.domain.Payment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserId(Long userId);
}
