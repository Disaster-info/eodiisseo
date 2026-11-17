package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.adapter.JpaChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswerResource;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerRepository;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerResourceRepository;
import com.disasterinfo.eodiisseo.dashboard.PostgresAuditSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL/FK/transaction tests, restricted to the disposable audit cluster. */
class PostgresChatAnswerStoreTest {

    private static final Instant CREATED = Instant.parse("2026-10-12T01:00:00Z");
    private static final Instant EXPIRY = CREATED.plus(Duration.ofDays(90));

    @Test
    void resourceForeignKeyFailureRollsBackTheAlreadyFlushedParent() throws Exception {
        try (PostgresAuditSupport database = new PostgresAuditSupport()) {
            UUID member = member(database);
            ChatAnswer answer = answer(member, EXPIRY);

            assertThatThrownBy(() -> database.tx(entityManager -> {
                store(database, entityManager).save(answer, List.of(UUID.randomUUID()));
                return null;
            })).isInstanceOf(RuntimeException.class);

            assertThat(database.count("ai_answer")).isZero();
            assertThat(database.count("ai_answer_resource")).isZero();
            assertThat(database.count("member")).isEqualTo(1);
        }
    }

    @Test
    void expiryIncludesTheExactBoundaryAndDeletesForeignKeyChildrenBeforeTheParent() throws Exception {
        try (PostgresAuditSupport database = new PostgresAuditSupport()) {
            UUID member = member(database);
            UUID resource = resource(database);
            ChatAnswer expiring = answer(member, EXPIRY);
            ChatAnswer preserved = answer(member, EXPIRY.plusSeconds(1));
            database.tx(entityManager -> {
                JpaChatAnswerStore store = store(database, entityManager);
                store.save(expiring, List.of(resource, resource));
                store.save(preserved, List.of(resource));
                return null;
            });
            assertThat(database.count("ai_answer")).isEqualTo(2);
            assertThat(database.count("ai_answer_resource")).isEqualTo(2);

            // PostgreSQL timestamptz stores microseconds; use its representable preceding instant.
            assertThat(database.<Integer>tx(entityManager -> store(database, entityManager)
                    .deleteExpired(EXPIRY.minusNanos(1_000)))).isZero();
            assertThat(database.<Integer>tx(entityManager -> store(database, entityManager)
                    .deleteExpired(EXPIRY))).isEqualTo(1);

            assertThat(database.count("ai_answer")).isEqualTo(1);
            assertThat(database.count("ai_answer_resource")).isEqualTo(1);
            database.tx(entityManager -> {
                assertThat(entityManager.find(ChatAnswer.class, expiring.getAnswerId())).isNull();
                assertThat(entityManager.find(ChatAnswer.class, preserved.getAnswerId())).isNotNull();
                return null;
            });
        }
    }

    @Test
    void withdrawalDeletesOnlyTheRequestedMembersAnswerAndGroundingLinks() throws Exception {
        try (PostgresAuditSupport database = new PostgresAuditSupport()) {
            UUID withdrawnMember = member(database);
            UUID otherMember = member(database);
            UUID resource = resource(database);
            ChatAnswer withdrawnAnswer = answer(withdrawnMember, EXPIRY);
            ChatAnswer otherAnswer = answer(otherMember, EXPIRY);
            database.tx(entityManager -> {
                JpaChatAnswerStore store = store(database, entityManager);
                store.save(withdrawnAnswer, List.of(resource));
                store.save(otherAnswer, List.of(resource));
                return null;
            });

            assertThat(database.<Integer>tx(entityManager -> store(database, entityManager)
                    .deleteByMemberId(withdrawnMember))).isEqualTo(1);

            assertThat(database.count("ai_answer")).isEqualTo(1);
            assertThat(database.count("ai_answer_resource")).isEqualTo(1);
            assertThat(database.count("official_resource")).isEqualTo(1);
            database.tx(entityManager -> {
                assertThat(entityManager.find(ChatAnswer.class, withdrawnAnswer.getAnswerId())).isNull();
                assertThat(entityManager.find(ChatAnswer.class, otherAnswer.getAnswerId()).getMemberId())
                        .isEqualTo(otherMember);
                assertThat(entityManager.find(ChatAnswerResource.class,
                        new ChatAnswerResource.Key(otherAnswer.getAnswerId(), resource))).isNotNull();
                return null;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(Deletion.class)
    void bulkDeletionCannotLeaveDeletedPersonalDataVisibleInTheSamePersistenceContext(Deletion deletion) throws Exception {
        try (PostgresAuditSupport database = new PostgresAuditSupport()) {
            UUID member = member(database);
            UUID resource = resource(database);
            ChatAnswer answer = answer(member, EXPIRY);
            database.tx(entityManager -> {
                JpaChatAnswerStore store = store(database, entityManager);
                store.save(answer, List.of(resource));
                ChatAnswer managedAnswer = entityManager.find(ChatAnswer.class, answer.getAnswerId());
                ChatAnswerResource.Key key = new ChatAnswerResource.Key(answer.getAnswerId(), resource);
                ChatAnswerResource managedResource = entityManager.find(ChatAnswerResource.class, key);
                assertThat(entityManager.contains(managedAnswer)).isTrue();
                assertThat(entityManager.contains(managedResource)).isTrue();

                int deleted = deletion == Deletion.WITHDRAWAL
                        ? store.deleteByMemberId(member) : store.deleteExpired(EXPIRY);
                assertThat(deleted).isEqualTo(1);
                assertThat(((Number) entityManager.createNativeQuery("select count(*) from ai_answer")
                        .getSingleResult()).longValue()).isZero();
                assertThat(((Number) entityManager.createNativeQuery("select count(*) from ai_answer_resource")
                        .getSingleResult()).longValue()).isZero();

                assertThat(entityManager.find(ChatAnswer.class, answer.getAnswerId())).isNull();
                assertThat(entityManager.find(ChatAnswerResource.class, key)).isNull();
                return null;
            });
        }
    }

    private static JpaChatAnswerStore store(PostgresAuditSupport database, EntityManager entityManager) {
        return new JpaChatAnswerStore(Optional.of(database.repository(entityManager, ChatAnswerRepository.class)),
                Optional.of(database.repository(entityManager, ChatAnswerResourceRepository.class)));
    }

    private static UUID member(PostgresAuditSupport database) {
        UUID id = UUID.randomUUID();
        database.sql("insert into member(member_id, status) values ('" + id + "', 'ACTIVE')");
        return id;
    }

    private static UUID resource(PostgresAuditSupport database) {
        UUID id = UUID.randomUUID();
        database.sql("insert into official_resource(resource_id) values ('" + id + "')");
        return id;
    }

    private static ChatAnswer answer(UUID member, Instant expiry) {
        return new ChatAnswer(UUID.randomUUID(), member, "테스트 질문", "공식 근거 테스트 안내",
                ChatAnswer.Status.COMPLETED, null, CREATED, CREATED, expiry);
    }

    enum Deletion {
        WITHDRAWAL, EXPIRY
    }
}
