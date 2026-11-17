package com.disasterinfo.eodiisseo.notification.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface NotificationSettingRepository extends JpaRepository<NotificationEntities.SettingRecord, UUID> {
    List<NotificationEntities.SettingRecord> findByEnabledTrue();
}
