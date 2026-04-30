package com.zeti.api.address;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressUpdateRequest(
        @NotBlank @Size(max = 100) String recipientName,
        @NotBlank @Size(max = 20) String recipientPhone,
        @Size(max = 10) String postalCode,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 20) String doorPassword,
        String deliveryNote,
        Boolean isDefault) {
}
