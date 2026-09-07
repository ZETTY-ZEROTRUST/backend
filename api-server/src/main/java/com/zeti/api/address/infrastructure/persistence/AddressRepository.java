package com.zeti.api.address.infrastructure.persistence;

import com.zeti.api.address.domain.Address;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByUserIdOrderByIsDefaultDescAddressIdAsc(Long userId);

    Optional<Address> findByAddressIdAndUserId(Long addressId, Long userId);
}
