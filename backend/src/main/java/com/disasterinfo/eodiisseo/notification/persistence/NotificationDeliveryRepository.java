package com.disasterinfo.eodiisseo.notification.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface NotificationDeliveryRepository extends JpaRepository<NotificationEntities.DeliveryRecord, UUID> {
    List<NotificationEntities.DeliveryRecord> findByEventId(UUID eventId);
}
