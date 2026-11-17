package com.disasterinfo.eodiisseo.ai.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Join Entity only; official_resource schema and lifecycle belong to ai.resource. */
@Entity
@Table(name = "ai_answer_resource")
@IdClass(ChatAnswerResource.Key.class)
public class ChatAnswerResource {

    @Id
    @Column(name = "answer_id", nullable = false)
    private UUID answerId;

    @Id
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    protected ChatAnswerResource() {
    }

    public ChatAnswerResource(UUID answerId, UUID resourceId) {
        this.answerId = Objects.requireNonNull(answerId);
        this.resourceId = Objects.requireNonNull(resourceId);
    }

    public UUID getAnswerId() { return answerId; }
    public UUID getResourceId() { return resourceId; }

    public static class Key implements Serializable {
        private UUID answerId;
        private UUID resourceId;

        public Key() {
        }

        public Key(UUID answerId, UUID resourceId) {
            this.answerId = answerId;
            this.resourceId = resourceId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(answerId, key.answerId)
                    && Objects.equals(resourceId, key.resourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(answerId, resourceId);
        }
    }
}
