package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardAuthenticationTest {
    private static final UUID MEMBER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final DashboardQuery national = new DashboardQuery(null, null, null);

    @Test
    void publicEntryRequiresTheAuthenticationAdapterEvenWithDisasterData() {
        DashboardService service = new DashboardService(Optional.empty(),
                Optional.of((member, criteria, base) -> List.of()), Optional.empty());

        assertThatThrownBy(() -> service.getDashboard(MEMBER, SESSION, national))
                .isInstanceOfSatisfying(DashboardException.class, exception -> assertThat(exception.reason())
                        .isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void orchestrationWithoutSessionIsNotAPublicApi() throws Exception {
        assertThat(Modifier.isPublic(DashboardService.class
                .getDeclaredMethod("getDashboard", UUID.class, DashboardQuery.class).getModifiers())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SessionState.class)
    void unverifiedMemberOrSessionNeverReachesDataQueries(SessionState state) {
        int[] readerCalls = {0};
        DashboardDisasterReader reader = (member, criteria, base) -> { readerCalls[0]++; return List.of(); };
        DashboardSessionVerifier verifier = (member, session) -> {
            if (state != SessionState.VALID || !MEMBER.equals(member) || !SESSION.equals(session)) {
                throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED, "Session is invalid.");
            }
        };
        DashboardService service = new DashboardService(Optional.empty(), Optional.of(reader),
                Optional.empty(), Optional.of(verifier));
        UUID member = state == SessionState.WRONG_OWNER ? UUID.randomUUID() : MEMBER;
        UUID session = state == SessionState.MISSING ? null : SESSION;

        if (state == SessionState.VALID) {
            assertThat(service.getDashboard(member, session, national).majorDisasters()).isEmpty();
            assertThat(readerCalls[0]).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> service.getDashboard(member, session, national))
                    .isInstanceOfSatisfying(DashboardException.class, exception -> assertThat(exception.reason())
                            .isEqualTo(DashboardException.Reason.UNAUTHENTICATED));
            assertThat(readerCalls[0]).isZero();
        }
    }

    @Test
    void sessionIsCheckedAgainOnEveryCall() {
        int[] checks = {0};
        DashboardService service = new DashboardService(Optional.empty(),
                Optional.of((member, criteria, base) -> List.of()), Optional.empty(),
                Optional.of((member, session) -> {
                    if (++checks[0] > 1) {
                        throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED, "Session was revoked.");
                    }
                }));
        service.getDashboard(MEMBER, SESSION, national);

        assertThatThrownBy(() -> service.getDashboard(MEMBER, SESSION, national)).isInstanceOf(DashboardException.class);
        assertThat(checks[0]).isEqualTo(2);
    }

    enum SessionState { VALID, MISSING, EXPIRED, REVOKED, INACTIVE_MEMBER, WRONG_OWNER }
}
