package com.zeti.api.mypage.application;

import com.zeti.api.address.application.AddressService;
import com.zeti.api.address.application.dto.AddressResponse;
import com.zeti.api.mypage.application.dto.MyPageResponse;
import com.zeti.api.order.application.OrderService;
import com.zeti.api.order.application.dto.OrderSummaryResponse;
import com.zeti.api.payment.application.PaymentService;
import com.zeti.api.payment.application.dto.PaymentResponse;
import com.zeti.api.user.application.UserService;
import com.zeti.api.user.application.dto.UserResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyPageService {


    private final UserService userService;
    private final AddressService addressService;
    private final OrderService orderService;
    private final PaymentService paymentService;

    public MyPageResponse getMyPage(Long userId) {
        UserResponse user = userService.getById(userId);

        List<AddressResponse> addresses = addressService.listByUserId(userId);
        AddressResponse defaultAddress = addresses.isEmpty() ? null : addresses.get(0);

        List<OrderSummaryResponse> recentOrders = orderService.recentByUserId(userId);

        List<PaymentResponse> payments = paymentService.listBalances(userId);

        return new MyPageResponse(user, defaultAddress, recentOrders, payments);
    }
}
