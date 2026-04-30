package com.zeti.api.mypage;

import com.zeti.api.address.AddressResponse;
import com.zeti.api.order.OrderSummaryResponse;
import com.zeti.api.payment.PaymentResponse;
import com.zeti.api.user.UserResponse;
import java.util.List;

public record MyPageResponse(
        UserResponse user,
        AddressResponse defaultAddress,
        List<OrderSummaryResponse> recentOrders,
        List<PaymentResponse> payments) {
}
