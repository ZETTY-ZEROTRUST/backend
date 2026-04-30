package com.zeti.api.mypage;

import com.zeti.api.address.AddressResponse;
import com.zeti.api.address.AddressService;
import com.zeti.api.order.OrderService;
import com.zeti.api.order.OrderSummaryResponse;
import com.zeti.api.payment.PaymentResponse;
import com.zeti.api.payment.PaymentService;
import com.zeti.api.user.UserResponse;
import com.zeti.api.user.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyPageService {

    private static final int RECENT_ORDER_LIMIT = 5;

    private final UserService userService;
    private final AddressService addressService;
    private final OrderService orderService;
    private final PaymentService paymentService;

    public MyPageResponse getMyPage(Long userId) {
        UserResponse user = userService.getById(userId);

        List<AddressResponse> addresses = addressService.listByUserId(userId);
        AddressResponse defaultAddress = addresses.isEmpty() ? null : addresses.get(0);

        List<OrderSummaryResponse> recentOrders = orderService.listByUserId(userId).stream()
                .limit(RECENT_ORDER_LIMIT)
                .toList();

        List<PaymentResponse> payments = paymentService.listBalances(userId);

        return new MyPageResponse(user, defaultAddress, recentOrders, payments);
    }
}
