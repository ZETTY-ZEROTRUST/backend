package com.zeti.api.address;

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
    public AddressResponse update(Long addressId, AddressUpdateRequest request) {
        Address address = addressRepository.findById(addressId)
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
