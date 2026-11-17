package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.adapter.JpaChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswerResource;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerRepository;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerResourceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Repository contract tests; real PostgreSQL/FK/transaction testing is pending the common DB setup. */
class JpaChatAnswerStoreTest {

    private final ChatAnswerRepository answers = mock(ChatAnswerRepository.class);
    private final ChatAnswerResourceRepository resources = mock(ChatAnswerResourceRepository.class);
    private final JpaChatAnswerStore store = new JpaChatAnswerStore(Optional.of(answers), Optional.of(resources));

    @Test
    @SuppressWarnings("unchecked")
    void savesOwnAnswerBeforeDeduplicatedResourceLinks() {
        UUID member = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        ChatAnswer answer = new ChatAnswer(answerId, member, "질문", "안내", ChatAnswer.Status.COMPLETED, null, Instant.EPOCH, Instant.EPOCH,
                Instant.EPOCH.plus(java.time.Duration.ofDays(90)));

        store.save(answer, List.of(resourceId, resourceId));

        ArgumentCaptor<Iterable<ChatAnswerResource>> captor = ArgumentCaptor.forClass(Iterable.class);
        var ordered = inOrder(answers, resources);
        ordered.verify(answers).saveAndFlush(answer);
        ordered.verify(resources).saveAllAndFlush(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getAnswerId()).isEqualTo(answerId);
            assertThat(link.getResourceId()).isEqualTo(resourceId);
        });
    }

    @Test
    void expirationDeletesLinksBeforeAnswersAtTheSameCutoff() {
        Instant cutoff = Instant.parse("2026-10-12T01:00:00Z");
        when(answers.deleteExpired(cutoff)).thenReturn(2);
        assertThat(store.deleteExpired(cutoff)).isEqualTo(2);
        var ordered = inOrder(resources, answers);
        ordered.verify(resources).deleteForExpiredAnswers(cutoff);
        ordered.verify(answers).deleteExpired(cutoff);
    }

    @Test
    void withdrawalDeletesOnlyThatMembersLinksThenAnswers() {
        UUID memberId = UUID.randomUUID();
        when(answers.deleteForMember(memberId)).thenReturn(3);
        assertThat(store.deleteByMemberId(memberId)).isEqualTo(3);
        var ordered = inOrder(resources, answers);
        ordered.verify(resources).deleteForMemberAnswers(memberId);
        ordered.verify(answers).deleteForMember(memberId);
    }

    @Test
    void unavailableRealRepositoriesAreNotReplacedWithAnInMemoryProductionStore() {
        JpaChatAnswerStore unavailable = new JpaChatAnswerStore(Optional.empty(), Optional.empty());
        assertThat(unavailable.isAvailable()).isFalse();
        assertThatThrownBy(() -> unavailable.deleteExpired(Instant.EPOCH))
                .isInstanceOfSatisfying(ChatException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ChatException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void compositeKeyContainsOnlyTheDocumentedAnswerAndResourceIdentifiers() {
        UUID answerId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        assertThat(new ChatAnswerResource.Key(answerId, resourceId))
                .isEqualTo(new ChatAnswerResource.Key(answerId, resourceId))
                .hasSameHashCodeAs(new ChatAnswerResource.Key(answerId, resourceId))
                .isNotEqualTo(new ChatAnswerResource.Key(answerId, UUID.randomUUID()));
        assertThat(ChatAnswer.Status.values()).containsExactly(ChatAnswer.Status.COMPLETED,
                ChatAnswer.Status.INSUFFICIENT, ChatAnswer.Status.FALLBACK, ChatAnswer.Status.FAILED, ChatAnswer.Status.TIMEOUT);
    }
}
