package com.zeti.api.address.presentation;

import com.zeti.api.address.application.AddressService;
import com.zeti.api.address.application.dto.AddressResponse;
import com.zeti.api.address.application.dto.AddressUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public List<AddressResponse> getMyAddresses(@AuthenticationPrincipal Long userId) {
        return addressService.listByUserId(userId);
    }

    @PutMapping("/{addressId}")
    public AddressResponse updateAddress(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long addressId,
            @Valid @RequestBody AddressUpdateRequest request) {
        return addressService.update(userId, addressId, request);
    }
}
