package com.zeti.api.address.application;

import com.zeti.api.address.application.dto.AddressResponse;
import com.zeti.api.address.application.dto.AddressUpdateRequest;
import com.zeti.api.address.domain.Address;
import com.zeti.api.address.infrastructure.persistence.AddressRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

    private final AddressRepository addressRepository;

    public List<AddressResponse> listByUserId(Long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescAddressIdAsc(userId).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressUpdateRequest request) {
        Address address = addressRepository.findByAddressIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        address.updateAddress(
                request.recipientName(),
                request.recipientPhone(),
                request.postalCode(),
                request.addressLine1(),
                request.addressLine2(),
                request.doorPassword(),
                request.deliveryNote(),
                request.isDefault());
        return AddressResponse.from(address);
    }
}
