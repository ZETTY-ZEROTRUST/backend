package com.zeti.api.address;

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

    // 정상 엔드포인트: JWT sub 기반 본인 배송지 목록
    @GetMapping
    public List<AddressResponse> getMyAddresses(@AuthenticationPrincipal Long userId) {
        return addressService.listByUserId(userId);
    }

    // 의도된 IDOR: path param userId vs JWT sub 일치 검증 없음
    // 응답에 door_password 평문 노출 — 쿠팡 유출 사고에서 가장 민감한 카테고리 재현 (시연 자산)
    @GetMapping("/{userId}")
    public List<AddressResponse> getAddressesByUser(@PathVariable Long userId) {
        return addressService.listByUserId(userId);
    }

    // 배송지 수정: 인가 검증 없음 (addressId만 알면 수정 가능, 잠재적 IDOR)
    @PutMapping("/{addressId}")
    public AddressResponse updateAddress(
            @PathVariable Long addressId,
            @Valid @RequestBody AddressUpdateRequest request) {
        return addressService.update(addressId, request);
    }
}
