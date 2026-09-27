package com.zeti.api.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zeti.api.order.domain.Order;
import com.zeti.api.order.infrastructure.persistence.OrderItemRepository;
import com.zeti.api.order.infrastructure.persistence.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** 개선 확인: 마이페이지는 top5, 목록은 Pageable로 DB에 위임한다(전량 조회 안 함). */
class OrderPaginationServiceTest {

    @Test
    void recentUsesTop5RepositoryMethod() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findTop5ByUserIdOrderByOrderedAtDesc(7L)).thenReturn(List.of());
        OrderService service = new OrderService(repo, mock(OrderItemRepository.class));

        service.recentByUserId(7L);

        // 전량 조회 메서드가 아니라 top5를 호출해야 한다.
        org.mockito.Mockito.verify(repo).findTop5ByUserIdOrderByOrderedAtDesc(7L);
    }

    @Test
    void listDelegatesPageableToRepository() {
        OrderRepository repo = mock(OrderRepository.class);
        Pageable pageable = PageRequest.of(0, 20);
        when(repo.findByUserIdOrderByOrderedAtDesc(8L, pageable))
                .thenReturn(new PageImpl<Order>(List.of()));
        OrderService service = new OrderService(repo, mock(OrderItemRepository.class));

        var page = service.listByUserId(8L, pageable);

        assertThat(page).isNotNull();
        org.mockito.Mockito.verify(repo).findByUserIdOrderByOrderedAtDesc(8L, pageable);
    }
}
