package com.zeti.api.order.application;

import com.zeti.api.order.infrastructure.persistence.OrderItemRepository;
import com.zeti.api.order.infrastructure.persistence.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    @Test
    void detailLooksUpOrderWithinAuthenticatedOwner() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderItemRepository itemRepository = mock(OrderItemRepository.class);
        OrderService service = new OrderService(orderRepository, itemRepository);

        when(orderRepository.findByOrderIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(7L, 99L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(orderRepository).findByOrderIdAndUserId(99L, 7L);
    }
}
