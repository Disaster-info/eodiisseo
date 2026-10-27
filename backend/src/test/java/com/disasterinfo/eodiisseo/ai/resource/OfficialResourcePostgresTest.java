package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual PostgreSQL I/O, with synthetic guidance and test-only review approvals. */
class OfficialResourcePostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");

    @Test void existingErdColumnsRoundTripVerbatimWithoutNewSchemaFields() {
        try (var database = new ResourcePostgresFixture()) {
            var store = database.store();
            var document = document(UUID.randomUUID(), "테스트 전용 지진 '원문'", "  지진 때 몸을 보호하세요.\r\n조건을 확인하세요.  ", NOW.minusSeconds(60).plusNanos(123456789));
            assertThat(store.isAvailable()).isTrue();
            assertThat(store.append(document).revisionFingerprint()).isEqualTo(document.revisionFingerprint());
            var loaded = store.findById(document.getResourceId()).orElseThrow();
            assertThat(loaded.getContentText()).isEqualTo(document.getContentText());
            assertThat(loaded.getTitle()).isEqualTo(document.getTitle());
            assertThat(loaded.getAgencyName()).isEqualTo(document.getAgencyName());
            assertThat(loaded.getSourceUrl()).isEqualTo(document.getSourceUrl());
            assertThat(loaded.getVersionLabel()).isEqualTo(document.getVersionLabel());
            assertThat(loaded.getCheckedAt()).isEqualTo(document.getCheckedAt());
            assertThat(store.findById(UUID.randomUUID())).isEmpty();
            assertThat(database.jdbc().queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='official_resource'", Long.class)).isEqualTo(7L);
        }
    }

    @Test void publicationsAreInsertOnlyAndChangedVersionRequiresNewId() {
        try (var database = new ResourcePostgresFixture()) {
            var store = database.store();
            UUID id = UUID.randomUUID();
            var original = document(id, "지진 테스트 Fixture", "지진 때 안내를 확인하세요.", NOW.minusSeconds(60));
            store.append(original);
            store.append(original);
            var changed = document(id, original.getTitle(), "지진 때 변경 안내를 확인하세요.", original.getCheckedAt());
            assertThatThrownBy(() -> store.append(changed)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.findById(id).orElseThrow().getContentText()).isEqualTo(original.getContentText());
            store.append(document(UUID.randomUUID(), changed.getTitle(), changed.getContentText(), NOW));
            assertThat(database.jdbc().queryForObject("select count(*) from official_resource", Long.class)).isEqualTo(2L);
        }
    }

    @Test void literalPreparedSearchHasStableOrderingLimitsAndNoSqlOrWildcardExpansion() {
        try (var database = new ResourcePostgresFixture()) {
            var store = database.store();
            UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
            store.append(document(second, "호우 테스트 Fixture", "침수된 도로에는 진입하지 마세요.", NOW));
            store.append(document(first, "지진 테스트 Fixture", "지진 때 몸을 보호하세요.", NOW));
            store.append(document(UUID.randomUUID(), "산불 테스트 Fixture", "산불 대피 안내를 확인하세요.", NOW.minusSeconds(60)));
            assertThat(store.searchCandidates(List.of("지진", "호우", "산불"), 2)).extracting(OfficialResourceDocument::getResourceId).containsExactly(first, second);
            assertThat(store.searchCandidates(List.of("%' or 1=1 --"), 60)).isEmpty();
            assertThat(store.searchCandidates(List.of("%"), 60)).isEmpty();
            assertThat(store.searchCandidates(List.of("_"), 60)).isEmpty();
            assertThatThrownBy(() -> store.searchCandidates(List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.searchCandidates(List.of("지진"), 61)).isInstanceOf(IllegalArgumentException.class);
            assertThat(database.jdbc().queryForObject("select count(*) from official_resource", Long.class)).isEqualTo(3L);
        }
    }

    @Test void realDatabaseRetrievalRequiresExactCurrentReviewAndEmitsOnlyMatchingParagraphs() {
        try (var database = new ResourcePostgresFixture()) {
            var store = database.store();
            String paragraph = "테스트 Fixture: 지진 때 실내에서는 탁자 아래에서 몸을 보호하세요.";
            var document = document(UUID.randomUUID(), "지진 테스트 Fixture", paragraph + "\n\n참고자료 목록", NOW.minusSeconds(60));
            store.append(document);
            ResourceApprovalPort approval = id -> Optional.of(new ResourceApprovalPort.Approval(id, document.revisionFingerprint(),
                    NOW.minusSeconds(30), NOW.plusSeconds(60), true));
            var service = new ResourceService(store, new KoreanLexicalParagraphSearch(), Optional.of(approval), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(service.search("땅이 흔들릴 때 실내에서 어떻게 해야 하나요?").resources())
                    .singleElement().satisfies(value -> {
                        assertThat(value.resourceId()).isEqualTo(document.getResourceId());
                        assertThat(value.content()).isEqualTo(paragraph);
                    });
            database.jdbc().update("update official_resource set content_text=? where resource_id=?", "지진 때 변조 안내를 확인하세요.", document.getResourceId());
            assertThat(service.search("지진 때 어떻게 해야 하나요?").resources()).isEmpty();
        }
    }

    private static OfficialResourceDocument document(UUID id, String title, String content, Instant checkedAt) {
        return new OfficialResourceDocument(id, "행정안전부", title, "https://www.safety24.go.kr/fixture", "fixture-v1", content, checkedAt);
    }
}
