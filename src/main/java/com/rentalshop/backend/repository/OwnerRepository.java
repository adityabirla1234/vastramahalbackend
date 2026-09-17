package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OwnerRepository extends JpaRepository<Owner, Long> {

    /**
     * Looked up on EVERY authenticated request (see DeviceAuthInterceptor),
     * so device_token has a unique index (schema.sql) to keep this fast even
     * as the owners table grows beyond the initial 3 rows.
     */
    Optional<Owner> findByDeviceTokenHash(String deviceTokenHash);

    /**
     * PushSyncService's fan-out list: every device that could receive a
     * push right now. Deactivated devices are excluded even if they still
     * hold a token, since a revoked device (Owner.DeviceStatus.INACTIVE)
     * shouldn't learn about new activity any more than it should be able to
     * call the API. The acting device itself is filtered out in
     * PushSyncService, not here, since that filter needs a nullable actor id.
     */
    List<Owner> findByDeviceStatusAndFcmTokenIsNotNull(Owner.DeviceStatus deviceStatus);
}
