package com.disasterinfo.eodiisseo.ai.chat.adapter;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswerResource;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerRepository;
import com.disasterinfo.eodiisseo.ai.chat.repository.ChatAnswerResourceRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Real persistence only; absent repositories fail explicitly and never simulate saved data.
 * Bulk deletion flushes pending changes and clears the shared persistence context. An enclosing
 * withdrawal transaction must reacquire any entity it needs to modify after this cleanup,
 * rather than modifying previously managed references that have now been detached.
 */
@Repository
public class JpaChatAnswerStore implements ChatAnswerStore {

    private final Optional<ChatAnswerRepository> answers;
    private final Optional<ChatAnswerResourceRepository> resources;

    public JpaChatAnswerStore(Optional<ChatAnswerRepository> answers,
                              Optional<ChatAnswerResourceRepository> resources) {
        this.answers = answers;
        this.resources = resources;
    }

    @Override
    public boolean isAvailable() {
        return answers.isPresent() && resources.isPresent();
    }

    @Override
    @Transactional
    public void save(ChatAnswer answer, List<UUID> usedResourceIds) {
        requireRepositories();
        answers.orElseThrow().saveAndFlush(answer);
        resources.orElseThrow().saveAllAndFlush(usedResourceIds.stream().distinct()
                .map(resourceId -> new ChatAnswerResource(answer.getAnswerId(), resourceId)).toList());
    }

    @Override
    @Transactional
    public int deleteExpired(Instant now) {
        requireRepositories();
        resources.orElseThrow().deleteForExpiredAnswers(now);
        return answers.orElseThrow().deleteExpired(now);
    }

    @Override
    @Transactional
    public int deleteByMemberId(UUID memberId) {
        requireRepositories();
        resources.orElseThrow().deleteForMemberAnswers(memberId);
        return answers.orElseThrow().deleteForMember(memberId);
    }

    private void requireRepositories() {
        if (!isAvailable()) {
            throw new ChatException(ChatException.Reason.DEPENDENCY_UNAVAILABLE,
                    "AI guidance persistence is not connected.");
        }
    }
}
