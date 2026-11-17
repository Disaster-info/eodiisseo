package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NotificationContractTest {
    @Test void settingsAndRegistrationResponsesMatchExactApiKeysAndHideTokens() {
        var mapper=JsonMapper.builder().build();UUID id=UUID.randomUUID();
        var settings=mapper.readTree(mapper.writeValueAsString(new SettingsResponse(true,List.of(id),"WARNING",false)));
        assertThat(settings.propertyNames()).containsExactlyInAnyOrder("enabled","interestAreaIds","minimumSeverity","nightNotificationEnabled");
        assertThat(settings.get("interestAreaIds").get(0).asString()).isEqualTo(id.toString());
        var registration=mapper.readTree(mapper.writeValueAsString(new DeviceResponse("test-install",true)));
        assertThat(registration.propertyNames()).containsExactlyInAnyOrder("deviceId","registered");assertThat(registration.has("fcmToken")).isFalse();
    }
    @Test void servicesStartWithoutInfrastructureAndNoHttpControllerIsActivated() {
        try(var context=new AnnotationConfigApplicationContext(NotificationService.class,NotificationDispatchService.class,NotificationRetentionService.class)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(NotificationContracts.Store.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class)).isEmpty();
            assertThatThrownBy(() -> context.getBean(NotificationService.class).getSettings(new NotificationContracts.MemberSession(UUID.randomUUID(),UUID.randomUUID())))
                    .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(500));
        }
    }
    @Test void domainErrorsSupplySafeCodeAndStatusForExistingGlobalErrorAdapter() {
        var failure=new NotificationException(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT);
        assertThat(failure.code()).isEqualTo("DEVICE_OWNERSHIP_CONFLICT");assertThat(failure.status()).isEqualTo(409);
        assertThat(failure.getMessage()).doesNotContain("token","secret");
    }
    @Test void officialSeverityCodeCannotBeUnicodeWhitespaceOnly() {
        assertThatThrownBy(() -> new NotificationContracts.Severity(UUID.randomUUID(),"\u00a0",4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
