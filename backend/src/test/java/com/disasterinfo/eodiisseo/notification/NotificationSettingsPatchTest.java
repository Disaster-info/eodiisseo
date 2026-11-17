package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.Store;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.SettingsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationSettingsPatchTest {
    @Test void omittedFieldsRetainTheirStoredValues() {
        var f = new NotificationFixture();
        var response = f.service().patchSettings(f.session,
                new SettingsRequest(false, null, null, null), Set.of("enabled"));

        assertThat(response.enabled()).isFalse();
        assertThat(response.interestAreaIds()).containsExactly(f.area1, f.area2);
        assertThat(response.minimumSeverity()).isEqualTo("WARNING");
        assertThat(response.nightNotificationEnabled()).isTrue();
    }

    @Test void anExplicitNullClearsTheMinimumAndDoesNotRequireLookingUpItsOldSeverity() {
        var f = new NotificationFixture();
        var service = new NotificationService(Optional.of(f.auth), Optional.of(f.store),
                Optional.of(f.interests), Optional.empty(), Optional.of(f.testCipher));
        var response = service.patchSettings(f.session, new SettingsRequest(true, List.of(), null, false),
                Set.of("enabled", "interestAreaIds", "minimumSeverity", "nightNotificationEnabled"));

        assertThat(response.minimumSeverity()).isNull();
        assertThat(f.store.findSettings(f.member).minimumSeverityId()).isNull();
        assertThat(response.interestAreaIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "interestAreaIds", "nightNotificationEnabled"})
    void aPresentRequiredFieldCannotBeNull(String field) {
        var f = new NotificationFixture(); var previous = f.store.findSettings(f.member);
        assertThatThrownBy(() -> f.service().patchSettings(f.session,
                new SettingsRequest(null, null, null, null), Set.of(field)))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.INVALID_REQUEST));
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
        assertThat(f.owned).allMatch(NotificationContracts.InterestArea::notifyEnabled);
    }

    @Test void unsupportedAtomicReadsFailClosedBeforeAnySettingsWrite() {
        var f = new NotificationFixture(); Store store = mock(Store.class, CALLS_REAL_METHODS);
        var service = new NotificationService(Optional.of(f.auth), Optional.of(store),
                Optional.of(f.interests), Optional.of(f.severityReader), Optional.of(f.testCipher));

        assertThatThrownBy(() -> service.patchSettings(f.session,
                new SettingsRequest(false, null, null, null), Set.of("enabled")))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE));
        verify(store).findSettingsForUpdate(f.member);
        verifyNoMoreInteractions(store);
    }
}
