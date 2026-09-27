package com.zeti.api.address.application;

import com.zeti.api.address.application.dto.AddressResponse;
import com.zeti.api.address.application.dto.AddressUpdateRequest;
import com.zeti.api.address.domain.Address;
import com.zeti.api.address.infrastructure.persistence.AddressRepository;
import java.util.List;
import com.zeti.api.mypage.application.MyPageCache;
import com.zeti.api.security.application.ObjectAccessDeniedException;
import com.zeti.api.securityevent.application.ApiSecurityEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

    private final AddressRepository addressRepository;
    private final MyPageCache myPageCache;
    private final ApiSecurityEventRecorder securityEvents;

    public List<AddressResponse> listByUserId(Long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescAddressIdAsc(userId).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressUpdateRequest request) {
        Address address = addressRepository.findByAddressIdAndUserId(addressId, userId)
                .orElseThrow(ObjectAccessDeniedException::new);
        address.updateAddress(
                request.recipientName(),
                request.recipientPhone(),
                request.postalCode(),
                request.addressLine1(),
                request.addressLine2(),
                request.doorPassword(),
                request.deliveryNote(),
                request.isDefault());
        // 업무 변경과 같은 트랜잭션: 함께 commit되고 함께 롤백된다.
        securityEvents.recordCommittedWrite();
        myPageCache.evictAfterCommit(userId);
        return AddressResponse.from(address);
    }
}
