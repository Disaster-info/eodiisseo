package com.disasterinfo.eodiisseo.ai.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Own ai_answer mapping, with scalar member UUID; member Entity remains owned by auth. */
@Entity
@Table(name = "ai_answer")
public class ChatAnswer {

    @Id
    @Column(name = "answer_id", nullable = false)
    private UUID answerId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    private String questionText;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private Status status;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ChatAnswer() {
    }

    public ChatAnswer(UUID answerId, UUID memberId, String questionText, String answerText, Status status,
                      String modelName, Instant createdAt, Instant completedAt, Instant approvedExpiresAt) {
        this.answerId = Objects.requireNonNull(answerId);
        this.memberId = Objects.requireNonNull(memberId);
        this.questionText = Objects.requireNonNull(questionText);
        this.answerText = answerText;
        this.status = Objects.requireNonNull(status);
        if (modelName != null && modelName.length() > 100) {
            throw new IllegalArgumentException("Model name exceeds the documented database length.");
        }
        this.modelName = modelName;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.completedAt = completedAt;
        this.expiresAt = Objects.requireNonNull(approvedExpiresAt);
    }

    public UUID getAnswerId() { return answerId; }
    public UUID getMemberId() { return memberId; }
    public String getQuestionText() { return questionText; }
    public String getAnswerText() { return answerText; }
    public Status getStatus() { return status; }
    public String getModelName() { return modelName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public enum Status {
        COMPLETED, INSUFFICIENT, FALLBACK, FAILED, TIMEOUT
    }
}
