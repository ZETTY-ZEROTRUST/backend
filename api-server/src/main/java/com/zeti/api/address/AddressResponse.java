package com.zeti.api.address;

public record AddressResponse(
        Long addressId,
        Long userId,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        String doorPassword,
        String deliveryNote,
        Boolean isDefault) {

    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getAddressId(),
                address.getUserId(),
                address.getRecipientName(),
                address.getRecipientPhone(),
                address.getPostalCode(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getDoorPassword(),
                address.getDeliveryNote(),
                address.getIsDefault());
    }
}
