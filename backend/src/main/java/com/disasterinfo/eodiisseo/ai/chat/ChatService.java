package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse.AnswerType;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse.Source;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer.Status;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GeneratedAnswer;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GenerationRequest;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatEvidenceGenerationPolicy;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatEvidenceCommitPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.OfficialResource;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.SearchResult;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatPromptPrivacyPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatRetentionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * FS-CHAT-001 orchestration. No HTTP route is enabled until real Bearer authentication,
 * the body schema and common error handling are available. No official/search/LLM data is invented.
 */
@Service
public class ChatService {

    public static final Duration RESPONSE_LIMIT = Duration.ofMinutes(10);
    private static final String FALLBACK_ANSWER = "공식기관에서 제공하는 재난 행동요령을 확인하세요.";

    private final Optional<ChatAuthenticationPort> authentication;
    private final Optional<ChatOfficialResourceSearchPort> officialSearch;
    private final Optional<ChatAnswerGenerationPort> generation;
    private final Optional<ChatAnswerStore> persistence;
    private final Optional<ChatRetentionPolicy> retention;
    private final Optional<ChatPromptPrivacyPort> promptPrivacy;
    private final Optional<ChatEvidenceCommitPort> completion;
    private final Clock clock;

    public ChatService(Optional<ChatAuthenticationPort> authentication,Optional<ChatOfficialResourceSearchPort> officialSearch,
            Optional<ChatAnswerGenerationPort> generation,Optional<ChatAnswerStore> persistence,Optional<ChatRetentionPolicy> retention,
            Optional<ChatPromptPrivacyPort> promptPrivacy){this(authentication,officialSearch,generation,persistence,retention,promptPrivacy,Clock.systemUTC(),Optional.empty());}
    @Autowired
    public ChatService(Optional<ChatAuthenticationPort> authentication,
                       Optional<ChatOfficialResourceSearchPort> officialSearch,
                       Optional<ChatAnswerGenerationPort> generation,
                       Optional<ChatAnswerStore> persistence,
                       Optional<ChatRetentionPolicy> retention,
                       Optional<ChatPromptPrivacyPort> promptPrivacy, Optional<ChatEvidenceCommitPort> completion) {
        this(authentication, officialSearch, generation, persistence, retention, promptPrivacy, Clock.systemUTC(), completion);
    }

    ChatService(Optional<ChatAuthenticationPort> authentication,
                Optional<ChatOfficialResourceSearchPort> officialSearch,
                Optional<ChatAnswerGenerationPort> generation,
                Optional<ChatAnswerStore> persistence, Optional<ChatRetentionPolicy> retention,
                Optional<ChatPromptPrivacyPort> promptPrivacy, Clock clock) {
        this(authentication, officialSearch, generation, persistence, retention, promptPrivacy, clock, Optional.empty());
    }
    public ChatService(Optional<ChatAuthenticationPort> authentication, Optional<ChatOfficialResourceSearchPort> officialSearch,
            Optional<ChatAnswerGenerationPort> generation, Optional<ChatAnswerStore> persistence, Optional<ChatRetentionPolicy> retention,
            Optional<ChatPromptPrivacyPort> promptPrivacy, Clock clock, Optional<ChatEvidenceCommitPort> completion) {
        this.completion=Objects.requireNonNull(completion);
        this.authentication = Objects.requireNonNull(authentication);
        this.officialSearch = Objects.requireNonNull(officialSearch);
        this.generation = Objects.requireNonNull(generation);
        this.persistence = Objects.requireNonNull(persistence);
        this.retention = Objects.requireNonNull(retention);
        this.promptPrivacy = Objects.requireNonNull(promptPrivacy);
        this.clock = Objects.requireNonNull(clock);
    }

    public ChatResponse requestGuidance(UUID authenticatedMemberId, UUID verifiedServiceSessionId,
                                        ChatRequest request) {
        Instant requestedAt = clock.instant();
        ChatAuthenticationPort auth = requireAuthentication(authenticatedMemberId, verifiedServiceSessionId);
        String question = requireQuestion(request);
        ChatAnswerStore store = requireStore();
        ChatRetentionPolicy retentionPolicy = requireRetentionPolicy();
        Outcome outcome = generate(authenticatedMemberId, question, requestedAt.plus(RESPONSE_LIMIT));
        Outcome[] finished={outcome}; ChatAnswer[] saved={null};
        try {
            auth.runWithActiveSession(authenticatedMemberId, verifiedServiceSessionId, () -> {
                recheckPrivacy(authenticatedMemberId,question,outcome);
                java.util.function.Consumer<Boolean> persist = current -> {
                    recheckPrivacy(authenticatedMemberId,question,outcome);
                    if(!current)finished[0]=fallback(Status.INSUFFICIENT).withQuestion(outcome.approvedQuestion());
                    Instant completedAt=clock.instant();
                    Instant expiresAt=approvedExpiry(retentionPolicy,requestedAt,completedAt);
                    var value=finished[0];
                    saved[0]=new ChatAnswer(UUID.randomUUID(),authenticatedMemberId,question,value.text(),value.status(),value.modelName(),requestedAt,completedAt,expiresAt);
                    saveSafely(store,saved[0],value.usedResources());
                    recheckPrivacy(authenticatedMemberId,question,outcome);
                };
                if(completion.isPresent()&&!outcome.usedResources().isEmpty())completion.orElseThrow().withCurrentEvidence(outcome.usedResources(),persist);
                else persist.accept(true);
            });
        } catch (ChatException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new ChatException(ChatException.Reason.UNAUTHENTICATED,"An authenticated active member and valid service session are required.");
        }
        return new ChatResponse(finished[0].status()==Status.COMPLETED?AnswerType.AI:AnswerType.FALLBACK,
            finished[0].text(),finished[0].usedResources().stream().map(r->new Source(r.organization(),r.title())).toList(),saved[0].getCompletedAt());
    }

    private void recheckPrivacy(UUID member,String question,Outcome outcome){
        try{if(promptPrivacy.isPresent()&&promptPrivacy.orElseThrow().localPersistenceRevoked(member))throw new IllegalStateException();}
        catch(RuntimeException denied){throw new ChatException(ChatException.Reason.POLICY_UNRESOLVED,"AI persistence permission was withdrawn.");}
        if(outcome.approvedQuestion()==null)return; // Preserve the existing local-only fallback when outbound was never approved.
        try {
            var current=promptPrivacy.orElseThrow().minimizeLocally(member,question);
            if(current==null||current.isEmpty()||!current.get().usableForOutbound()||!outcome.approvedQuestion().equals(current.get().safeQuestion()))throw new IllegalStateException();
        }catch(RuntimeException failure){throw new ChatException(ChatException.Reason.POLICY_UNRESOLVED,"AI processing permission changed before persistence.");}
    }
    /** Invoked by the existing retention scheduler after it is wired; no duplicate scheduler policy. */
    public int deleteExpiredAnswers() {
        try {
            return requireStore().deleteExpired(clock.instant());
        } catch (ChatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,
                    "AI guidance retention processing failed.");
        }
    }

    /** Internal withdrawal hook, never a client-controlled member deletion API. */
    public int deleteAnswersOnWithdrawal(UUID memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("Withdrawal member ID is required.");
        }
        try {
            return requireStore().deleteByMemberId(memberId);
        } catch (ChatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,
                    "AI guidance withdrawal deletion failed.");
        }
    }

    private Outcome generate(UUID memberId, String question, Instant deadlineAt) {
        if (officialSearch.isEmpty() || promptPrivacy.isEmpty()) {
            return fallback(Status.FALLBACK);
        }
        ChatDeadline deadline = new ChatDeadline(clock, deadlineAt);
        Outcome basicGuidance = fallback(Status.FALLBACK);
        try {
            var prepared = deadline.await(() -> promptPrivacy.orElseThrow().minimizeLocally(memberId, question));
            if (prepared == null || prepared.isEmpty() || !prepared.get().usableForOutbound()) {
                return basicGuidance;
            }
            String safeQuestion = prepared.get().safeQuestion();
            String searchQuestion=prepared.get().retrievalQuestion();
            basicGuidance=basicGuidance.withQuestion(safeQuestion);
            SearchResult searchResult = deadline.await(() -> officialSearch.orElseThrow().search(searchQuestion));
            basicGuidance = selectedBasicGuidance(searchResult).withQuestion(safeQuestion);
            Map<UUID, OfficialResource> evidence = validateEvidence(searchResult);
            if (evidence.isEmpty()) {
                return basicGuidance.withStatus(Status.INSUFFICIENT);
            }
            if (generation.isEmpty() || !deadline.await(() -> generation.orElseThrow().isConfigured())) {
                return basicGuidance;
            }
            var stillApproved=deadline.await(()->promptPrivacy.orElseThrow().minimizeLocally(memberId,question));
            if(stillApproved==null || stillApproved.isEmpty() || !stillApproved.get().usableForOutbound()
                    || !safeQuestion.equals(stillApproved.get().safeQuestion()))return basicGuidance;
            // Recheck current approvals after privacy/consent and immediately before outbound generation.
            Map<UUID,OfficialResource> currentEvidence=validateEvidence(deadline.await(()->officialSearch.orElseThrow().search(searchQuestion)));
            if(!currentEvidence.entrySet().containsAll(evidence.entrySet()))return fallback(Status.INSUFFICIENT).withQuestion(safeQuestion);
            ChatAnswerGenerationPort generator=generation.orElseThrow();
            if(officialSearch.orElseThrow() instanceof ChatEvidenceGenerationPolicy policy)generator=policy.guardGeneration(generator);
            ChatAnswerGenerationPort guarded=generator;
            GeneratedAnswer answer = deadline.await(() -> guarded
                    .generate(new GenerationRequest(safeQuestion, List.copyOf(evidence.values()), deadlineAt)));
            if (!validGeneratedAnswer(answer, evidence)) {
                return basicGuidance.withStatus(Status.FAILED);
            }
            var postModelPrivacy=deadline.await(()->promptPrivacy.orElseThrow().minimizeLocally(memberId,question));
            if(postModelPrivacy==null||postModelPrivacy.isEmpty()||!postModelPrivacy.get().usableForOutbound()||!safeQuestion.equals(postModelPrivacy.get().safeQuestion()))return fallback(Status.FALLBACK).withQuestion(safeQuestion);
            // A review can expire or be revoked while the model is working. Do not publish stale evidence.
            Map<UUID,OfficialResource> finalEvidence=validateEvidence(deadline.await(()->officialSearch.orElseThrow().search(searchQuestion)));
            if(answer.usedResourceIds().stream().anyMatch(id->!Objects.equals(evidence.get(id),finalEvidence.get(id))))
                return fallback(Status.INSUFFICIENT).withQuestion(safeQuestion);
            List<OfficialResource> used = answer.usedResourceIds().stream().distinct()
                    .map(evidence::get).toList();
            return new Outcome(Status.COMPLETED, answer.text(), answer.modelName(), used).withQuestion(safeQuestion);
        } catch (TimeoutException failure) {
            return basicGuidance.withStatus(Status.TIMEOUT);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return basicGuidance.withStatus(Status.FAILED);
        } catch (ExecutionException | RuntimeException failure) {
            // Provider details can contain prompts, locations, tokens or keys. Do not expose them.
            return basicGuidance.withStatus(Status.FAILED);
        }
    }

    private static Outcome selectedBasicGuidance(SearchResult searchResult) {
        if (searchResult == null || searchResult.basicGuidance() == null) {
            return fallback(Status.FALLBACK);
        }
        var selection = searchResult.basicGuidance();
        List<OfficialResource> selected = searchResult.resources().stream()
                .filter(resource -> Objects.equals(resource.resourceId(), selection.resourceId())).distinct().toList();
        if (selected.size() != 1 || !selected.getFirst().usableAsEvidence()
                || !ChatText.hasText(selection.excerpt())
                || !selected.getFirst().content().contains(selection.excerpt())) {
            return fallback(Status.FALLBACK);
        }
        return new Outcome(Status.FALLBACK, selection.excerpt(), null, selected);
    }

    private static Map<UUID, OfficialResource> validateEvidence(SearchResult searchResult) {
        if (searchResult == null || !searchResult.sufficientEvidence() || searchResult.resources().isEmpty()) {
            return Map.of();
        }
        Map<UUID, OfficialResource> result = new LinkedHashMap<>();
        for (OfficialResource resource : searchResult.resources()) {
            if (!resource.usableAsEvidence()) {
                return Map.of();
            }
            OfficialResource previous = result.putIfAbsent(resource.resourceId(), resource);
            if (previous != null && !previous.equals(resource)) {
                return Map.of();
            }
        }
        return result;
    }

    private static boolean validGeneratedAnswer(GeneratedAnswer answer, Map<UUID, OfficialResource> evidence) {
        return answer != null && answer.grounded() && ChatText.hasText(answer.text())
                && answer.usedResourceIds() != null && !answer.usedResourceIds().isEmpty()
                && answer.usedResourceIds().stream().allMatch(evidence::containsKey)
                && (answer.modelName() == null || answer.modelName().length() <= 100);
    }

    private ChatAuthenticationPort requireAuthentication(UUID memberId, UUID sessionId) {
        if (memberId == null || sessionId == null) {
            throw new ChatException(ChatException.Reason.UNAUTHENTICATED,
                    "An authenticated active member and valid service session are required.");
        }
        ChatAuthenticationPort auth = authentication.orElseThrow(() -> new ChatException(
                ChatException.Reason.DEPENDENCY_UNAVAILABLE, "AI guidance authentication is not connected."));
        try {
            auth.requireActiveSession(memberId, sessionId);
        } catch (ChatException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ChatException(ChatException.Reason.UNAUTHENTICATED,
                    "An authenticated active member and valid service session are required.");
        }
        return auth;
    }

    private static String requireQuestion(ChatRequest request) {
        if (request == null) {
            throw new ChatException(ChatException.Reason.INVALID_QUESTION, "A non-empty question is required.");
        }
        return request.validatedQuestion();
    }

    private ChatAnswerStore requireStore() {
        ChatAnswerStore store = persistence.orElseThrow(() -> new ChatException(
                ChatException.Reason.DEPENDENCY_UNAVAILABLE, "AI guidance persistence is not connected."));
        boolean available;
        try {
            available = store.isAvailable();
        } catch (RuntimeException failure) {
            available = false;
        }
        if (!available) {
            throw new ChatException(ChatException.Reason.DEPENDENCY_UNAVAILABLE,
                    "AI guidance persistence is not connected.");
        }
        return store;
    }

    private ChatRetentionPolicy requireRetentionPolicy() {
        ChatRetentionPolicy policy = retention.orElseThrow(ChatService::unresolvedRetentionPolicy);
        try {
            if (!policy.isApproved()) {
                throw unresolvedRetentionPolicy();
            }
        } catch (RuntimeException failure) {
            throw unresolvedRetentionPolicy();
        }
        return policy;
    }

    private static Instant approvedExpiry(ChatRetentionPolicy policy, Instant requestedAt, Instant completedAt) {
        try {
            Instant expiresAt = policy.approvedExpiresAt(requestedAt, completedAt);
            if (expiresAt == null || !expiresAt.isAfter(completedAt)) {
                throw unresolvedRetentionPolicy();
            }
            return expiresAt;
        } catch (RuntimeException failure) {
            throw unresolvedRetentionPolicy();
        }
    }

    private static ChatException unresolvedRetentionPolicy() {
        return new ChatException(ChatException.Reason.POLICY_UNRESOLVED,
                "AI guidance retention reference time requires a confirmed policy.");
    }

    private static void saveSafely(ChatAnswerStore store, ChatAnswer answer, List<OfficialResource> used) {
        try {
            store.save(answer, used.stream().map(OfficialResource::resourceId).toList());
        } catch (RuntimeException failure) {
            throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE, "AI guidance could not be saved.");
        }
    }

    private static Outcome fallback(Status status) {
        return new Outcome(status, FALLBACK_ANSWER, null, List.of());
    }

    private record Outcome(Status status, String text, String modelName, List<OfficialResource> usedResources,String approvedQuestion) {
        Outcome(Status status,String text,String modelName,List<OfficialResource> resources){this(status,text,modelName,resources,null);}
        Outcome withQuestion(String question){return new Outcome(status,text,modelName,usedResources,question);}
        Outcome withStatus(Status newStatus) {
            return new Outcome(newStatus, text, modelName, usedResources,approvedQuestion);
        }
    }
}
