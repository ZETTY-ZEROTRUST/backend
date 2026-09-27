package com.zeti.api.address.application;

import com.zeti.api.address.application.dto.AddressUpdateRequest;
import com.zeti.api.address.infrastructure.persistence.AddressRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddressServiceTest {

    @Test
    void updateLooksUpAddressWithinAuthenticatedOwner() {
        AddressRepository repository = mock(AddressRepository.class);
        AddressService service = new AddressService(repository,
                mock(com.zeti.api.mypage.application.MyPageCache.class));
        AddressUpdateRequest request = new AddressUpdateRequest(
                "수령인", "010-0000-0000", "12345", "주소", null, null, null, true);

        when(repository.findByAddressIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7L, 99L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repository).findByAddressIdAndUserId(99L, 7L);
    }
}
