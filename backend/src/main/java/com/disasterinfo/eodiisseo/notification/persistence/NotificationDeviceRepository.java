package com.disasterinfo.eodiisseo.notification.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface NotificationDeviceRepository extends JpaRepository<NotificationEntities.DeviceRecord, UUID> {
    Optional<NotificationEntities.DeviceRecord> findByDeviceId(String deviceId);
    List<NotificationEntities.DeviceRecord> findByMemberIdAndActiveTrue(UUID memberId);
}
