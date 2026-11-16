package com.disasterinfo.eodiisseo.notification;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.adapter.DevelopmentNotificationPolicies;
import com.disasterinfo.eodiisseo.ai.chat.adapter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class OperationalPoliciesTest {
    @ParameterizedTest @CsvSource({"2026-11-03T12:59:59Z,DAYTIME","2026-11-03T13:00:00Z,NIGHT","2026-11-03T20:59:59Z,NIGHT","2026-11-03T21:00:00Z,DAYTIME"})
    void configuredKoreanNightBoundary(String instant,TimeWindow expected) {
        assertThat(new DevelopmentNotificationPolicies().configuredNightPolicy("06:00").classify(Instant.parse(instant))).isEqualTo(expected);
    }
    @Test void configuredNightEndDoesNotResetAtMidnight() {
        assertThat(new DevelopmentNotificationPolicies().configuredNightPolicy("07:00").classify(Instant.parse("2026-11-03T21:30:00Z"))).isEqualTo(TimeWindow.NIGHT);
    }
    @Test void closedVocabularyNeverCopiesNamesContactCoordinatesOrSecrets() {
        var denied=new LocalIntentPrivacy(id->false);
        var allowed=new LocalIntentPrivacy(id->true);UUID member=UUID.randomUUID();
        String question="홍길동 user@example.com 010-1234-5678 서울시 비공개주소 37.123 127.456 지진 대피 방법";
        assertThat(denied.minimizeLocally(member,question)).isEmpty();
        assertThat(allowed.minimizeLocally(question)).isEmpty();
        assertThat(allowed.minimizeLocally(member,question)).get().satisfies(value->{
            assertThat(value.usableForOutbound()).isTrue();
            assertThat(value.safeQuestion()).isEqualTo("지진 발생 시 안전한 대피 방법");
        });
        assertThat(allowed.minimizeLocally(member,"지진 bearer private-token")).isEmpty();
        assertThat(allowed.minimizeLocally(member,"지진 산불 대피")).isEmpty();
        assertThat(allowed.minimizeLocally(member,"개인 신상만 포함")).isEmpty();
    }
    @Test void nightSuppressionHasAnExplicitReasonAndDoesNotCreateAMorningReplay() {
        var fixture=new NotificationFixture();
        fixture.store.saveSettings(fixture.member,new Settings(true,null,false,fixture.now));
        var report=fixture.dispatcher().process(new DisasterChange(fixture.disasterId,ChangeKind.NEW,null),Instant.parse("2026-11-03T13:00:00Z"));
        assertThat(report.suppressionReasons()).containsEntry("NIGHT_BLOCKED",1);
        assertThat(fixture.store.events).isEmpty();
        assertThat(fixture.sends).isZero();
    }
    @Test void retentionUsesRequestedUtcInstantRatherThanGenerationTime() {
        Instant requested=Instant.parse("2026-11-03T00:00:00Z");
        assertThat(new CreatedAtRetentionPolicy().approvedExpiresAt(requested,requested.plusSeconds(600))).isEqualTo(requested.plus(Duration.ofDays(90)));
    }
}
