package com.zeti.api.mypage.application.dto;

import com.zeti.api.address.application.dto.AddressResponse;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import com.zeti.api.payment.application.dto.PaymentResponse;
import com.zeti.api.user.application.dto.UserResponse;
import java.util.List;

public record MyPageResponse(
        UserResponse user,
        AddressResponse defaultAddress,
        List<OrderSummaryResponse> recentOrders,
        List<PaymentResponse> payments) {
}
