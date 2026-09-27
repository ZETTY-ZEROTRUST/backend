package com.zeti.api.mypage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.zeti.api.address.application.AddressService;
import com.zeti.api.mypage.application.dto.MyPageResponse;
import com.zeti.api.order.application.OrderService;
import com.zeti.api.payment.application.PaymentService;
import com.zeti.api.user.application.UserService;
import com.zeti.api.user.application.dto.UserResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cache-Aside: 적중이면 DB 경로(서비스)를 부르지 않고, 미스면 로드 후 저장한다. */
class MyPageServiceCacheTest {

    private final UserService users = mock(UserService.class);
    private final AddressService addresses = mock(AddressService.class);
    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final MyPageCache cache = mock(MyPageCache.class);
    private final MyPageService service = new MyPageService(users, addresses, orders, payments, cache);

    private final MyPageResponse sample = new MyPageResponse(
            new UserResponse(7L, "a@b", "n", "p", null), null, List.of(), List.of());

    @Test
    void hitSkipsDatabase() {
        when(cache.isEnabled()).thenReturn(true);
        when(cache.get(7L)).thenReturn(Optional.of(sample));

        assertThat(service.getMyPage(7L)).isEqualTo(sample);

        verifyNoInteractions(users, addresses, orders, payments);
        verify(cache, never()).put(anyLong(), any());
    }

    @Test
    void missLoadsFromDatabaseAndStores() {
        when(cache.isEnabled()).thenReturn(true);
        when(cache.get(7L)).thenReturn(Optional.empty());
        when(users.getById(7L)).thenReturn(sample.user());

        service.getMyPage(7L);

        verify(users).getById(7L);
        verify(cache).put(eq(7L), any(MyPageResponse.class));
    }

    @Test
    void disabledCacheAlwaysUsesDatabase() {
        when(cache.isEnabled()).thenReturn(false);
        when(users.getById(7L)).thenReturn(sample.user());

        service.getMyPage(7L);

        verify(users).getById(7L);
        verify(cache, never()).get(anyLong());
    }
}
