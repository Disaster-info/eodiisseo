package com.disasterinfo.eodiisseo.notification.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface NotificationEventRepository extends JpaRepository<NotificationEntities.EventRecord, UUID> {}
