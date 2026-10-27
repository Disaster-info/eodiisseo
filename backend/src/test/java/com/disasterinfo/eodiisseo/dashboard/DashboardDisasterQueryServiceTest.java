package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardDisasterQueryServiceTest {
    private static final UUID MEMBER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DISASTER = UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919");
    private static final Instant TIME = Instant.parse("2026-10-07T03:20:00Z");

    @ParameterizedTest
    @EnumSource(DisasterStatus.class)
    void everyRequestedStateIncludingRetainedEndedAndLiftedIsQueryable(DisasterStatus status) {
        FakeReader reader = new FakeReader();
        MajorDisaster item = item(DISASTER, "HEAVY_RAIN", status);
        reader.entries = List.of(new DashboardDisasterQueryReader.Entry(item, DataOrigin.OFFICIAL));

        assertThat(service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, status.name())).disasters()).containsExactly(item);
        assertThat(reader.lastCriteria.status()).isEqualTo(status);
    }

    @Test
    void filtersAreCombinedAndMockRecordsAreNeverOfficialListItems() {
        FakeReader reader = new FakeReader();
        MajorDisaster matching = item(DISASTER, "HEAVY_RAIN", DisasterStatus.ENDED);
        reader.entries = List.of(
                new DashboardDisasterQueryReader.Entry(item(UUID.randomUUID(), "EARTHQUAKE", DisasterStatus.ENDED), DataOrigin.OFFICIAL),
                new DashboardDisasterQueryReader.Entry(matching, DataOrigin.OFFICIAL),
                new DashboardDisasterQueryReader.Entry(item(UUID.randomUUID(), "HEAVY_RAIN", DisasterStatus.ONGOING), DataOrigin.OFFICIAL),
                new DashboardDisasterQueryReader.Entry(item(UUID.randomUUID(), "HEAVY_RAIN", DisasterStatus.ENDED), DataOrigin.MOCK));

        assertThat(service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery("HEAVY_RAIN", "ENDED")).disasters()).containsExactly(matching);
    }

    @Test
    void typeValidityUsesTheDomainCatalogRatherThanAnInventedEnum() {
        FakeReader reader = new FakeReader();
        reader.types = Set.of("NEW_OFFICIAL_TYPE");

        assertThat(service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery("NEW_OFFICIAL_TYPE", null)).disasters()).isEmpty();
        assertThat(reader.lastCriteria.type()).isEqualTo("NEW_OFFICIAL_TYPE");
    }

    @Test
    void emptyResultsAreNormalAndMissingOfficialSeverityRemainsNull() {
        FakeReader reader = new FakeReader();
        assertThat(service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, "ONGOING")).disasters()).isEmpty();
        reader.entries = List.of(new DashboardDisasterQueryReader.Entry(
                item(DISASTER, "HEAVY_RAIN", DisasterStatus.ONGOING), DataOrigin.OFFICIAL));

        assertThat(service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, "ONGOING")).disasters().getFirst().severity()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "UNKNOWN_TYPE"})
    void invalidTypesAreRejected(String type) {
        FakeReader reader = new FakeReader();
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(type, "ONGOING")), DashboardException.Reason.INVALID_QUERY);
        assertThat(reader.listCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ongoing", "ACTIVE", "UNKNOWN"})
    void invalidStatusesAreRejected(String status) {
        FakeReader reader = new FakeReader();
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, status)), DashboardException.Reason.INVALID_QUERY);
        assertThat(reader.listCalls).isZero();
    }

    @Test
    void unresolvedDefaultAndFreshnessPoliciesAreHeldRatherThanInvented() {
        FakeReader reader = new FakeReader();
        reader.defaultPolicyResolved = false;
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, null)), DashboardException.Reason.POLICY_UNRESOLVED);

        reader.defaultPolicyResolved = true;
        reader.freshnessRepresentable = false;
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, "ONGOING")), DashboardException.Reason.POLICY_UNRESOLVED);
    }

    @Test
    void defaultQueryIsPassedUnchangedOnceItsDomainPolicyIsExplicitlyReady() {
        FakeReader reader = new FakeReader();
        service(reader).getDisasters(MEMBER, SESSION, new DisasterListQuery(null, null));
        assertThat(reader.lastCriteria).isEqualTo(new DisasterListQuery.Criteria(null, null));
    }

    @Test
    void duplicateIdentifiersFailInsteadOfChoosingAnArbitraryRecord() {
        FakeReader reader = new FakeReader();
        var entry = new DashboardDisasterQueryReader.Entry(item(DISASTER, "HEAVY_RAIN", DisasterStatus.ONGOING), DataOrigin.OFFICIAL);
        reader.entries = List.of(entry, entry);
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, "ONGOING")), DashboardException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(DisasterStatus.class)
    void detailReturnsEveryStateWithoutSynthesizingMissingContent(DisasterStatus status) {
        FakeReader reader = new FakeReader();
        reader.detail = Optional.of(new DashboardDisasterQueryReader.DetailSnapshot(detail(DISASTER, status, "LATEST"), DataOrigin.OFFICIAL));

        DisasterDetailResponse response = service(reader).getDisaster(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.officialContent()).isNull();
        assertThat(response.source()).isNull();
        assertThat(response.severity()).isNull();
        assertThat(response.updatedAt()).isEqualTo(TIME);
    }

    @Test
    void nonexistentAndMockDetailsAreNotExposed() {
        FakeReader reader = new FakeReader();
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER), DashboardException.Reason.NOT_FOUND);
        reader.detail = Optional.of(new DashboardDisasterQueryReader.DetailSnapshot(
                detail(DISASTER, DisasterStatus.ONGOING, "LATEST"), DataOrigin.MOCK));
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER), DashboardException.Reason.NOT_FOUND);
    }

    @Test
    void missingFreshnessContractAndMismatchedDetailIdAreRejected() {
        FakeReader reader = new FakeReader();
        reader.detail = Optional.of(new DashboardDisasterQueryReader.DetailSnapshot(
                detail(DISASTER, DisasterStatus.ONGOING, null), DataOrigin.OFFICIAL));
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER), DashboardException.Reason.POLICY_UNRESOLVED);
        reader.detail = Optional.of(new DashboardDisasterQueryReader.DetailSnapshot(
                detail(UUID.randomUUID(), DisasterStatus.ONGOING, "LATEST"), DataOrigin.OFFICIAL));
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER), DashboardException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0", "\u2007", "\u202f"})
    void unicodeWhitespaceDoesNotSatisfyTheFreshnessContract(String dataStatus) {
        FakeReader reader = new FakeReader();
        reader.detail = Optional.of(new DashboardDisasterQueryReader.DetailSnapshot(
                detail(DISASTER, DisasterStatus.ONGOING, dataStatus), DataOrigin.OFFICIAL));
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER),
                DashboardException.Reason.POLICY_UNRESOLVED);
    }

    @Test
    void readerFailuresRemainFailuresRatherThanEmptyResults() {
        FakeReader reader = new FakeReader();
        reader.failure = new IllegalStateException("Test database unavailable.");
        assertThatThrownBy(() -> service(reader).getDisasters(MEMBER, SESSION,
                new DisasterListQuery(null, "ONGOING"))).isSameAs(reader.failure);
        assertThatThrownBy(() -> service(reader).getDisaster(MEMBER, SESSION, DISASTER)).isSameAs(reader.failure);
    }

    @Test
    void authenticationAndSessionAreRequiredBeforeAnyDataAccess() {
        FakeReader reader = new FakeReader();
        DashboardDisasterQueryService service = new DashboardDisasterQueryService(Optional.of(reader), Optional.empty());
        assertReason(() -> service.getDisasters(MEMBER, SESSION, new DisasterListQuery(null, null)),
                DashboardException.Reason.DEPENDENCY_UNAVAILABLE);
        assertReason(() -> service.getDisaster(MEMBER, null, DISASTER), DashboardException.Reason.UNAUTHENTICATED);
        assertThat(reader.listCalls).isZero();
        assertThat(reader.detailCalls).isZero();
    }

    @Test
    void missingQueryIdAndReaderFailExplicitly() {
        FakeReader reader = new FakeReader();
        assertReason(() -> service(reader).getDisasters(MEMBER, SESSION, null), DashboardException.Reason.INVALID_QUERY);
        assertReason(() -> service(reader).getDisaster(MEMBER, SESSION, null), DashboardException.Reason.INVALID_QUERY);
        DashboardDisasterQueryService service = new DashboardDisasterQueryService(Optional.empty(),
                Optional.of((member, session) -> { }));
        assertReason(() -> service.getDisaster(MEMBER, SESSION, DISASTER), DashboardException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void queryServiceStartsWithoutAnyAdapterBeans() {
        try (var context = new AnnotationConfigApplicationContext(DashboardDisasterQueryService.class)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(DashboardDisasterQueryService.class)).isNotNull();
        }
    }

    private static DashboardDisasterQueryService service(FakeReader reader) {
        return new DashboardDisasterQueryService(Optional.of(reader), Optional.of((member, session) -> {
            if (!MEMBER.equals(member) || !SESSION.equals(session)) {
                throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED, "Session is invalid.");
            }
        }));
    }

    private static MajorDisaster item(UUID id, String type, DisasterStatus status) {
        return new MajorDisaster(id, type, "Test official title", null, status, null, TIME, TIME);
    }

    private static DisasterDetailResponse detail(UUID id, DisasterStatus status, String dataStatus) {
        return new DisasterDetailResponse(id, "HEAVY_RAIN", "Test official title", null, TIME, null,
                status, null, null, TIME, dataStatus);
    }

    private static void assertReason(Runnable action, DashboardException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DashboardException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static class FakeReader implements DashboardDisasterQueryReader {
        private Set<String> types = Set.of("HEAVY_RAIN", "EARTHQUAKE");
        private List<Entry> entries = List.of();
        private Optional<DetailSnapshot> detail = Optional.empty();
        private boolean defaultPolicyResolved = true;
        private boolean freshnessRepresentable = true;
        private DisasterListQuery.Criteria lastCriteria;
        private RuntimeException failure;
        private int listCalls;
        private int detailCalls;

        @Override
        public boolean existsType(String typeCode) { return types.contains(typeCode); }

        @Override
        public ListSnapshot findDisasters(DisasterListQuery.Criteria criteria) {
            listCalls++;
            lastCriteria = criteria;
            if (failure != null) { throw failure; }
            return new ListSnapshot(entries, defaultPolicyResolved, freshnessRepresentable);
        }

        @Override
        public Optional<DetailSnapshot> findDisaster(UUID disasterId) {
            detailCalls++;
            if (failure != null) { throw failure; }
            return detail;
        }
    }
}
