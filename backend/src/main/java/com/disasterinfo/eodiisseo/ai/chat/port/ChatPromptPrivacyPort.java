package com.disasterinfo.eodiisseo.ai.chat.port;

import com.disasterinfo.eodiisseo.ai.chat.ChatText;

import java.util.Optional;

/**
 * NFR-AI-006 local minimization contract. Remove unnecessary personal data, precision location
 * and credentials before any external search/model call. This operation must not send the raw
 * question to an external service. No guessed regex/redaction policy is enabled by this domain.
 */
public interface ChatPromptPrivacyPort {

    Optional<PreparedQuestion> minimizeLocally(String rawQuestion);
    default Optional<PreparedQuestion> minimizeLocally(java.util.UUID memberId,String rawQuestion) { return minimizeLocally(rawQuestion); }

    /** Local persistence permission is separate from external-transmission approval. */
    default boolean localPersistenceRevoked(java.util.UUID memberId){return false;}
    record PreparedQuestion(String safeQuestion, boolean approvedForOutbound) {
        public boolean usableForOutbound() {
            return approvedForOutbound && ChatText.hasText(safeQuestion);
        }

        /** Only the known closed generic templates are reformatted for retrieval, never raw personal text. */
        public String retrievalQuestion(){
            if(safeQuestion==null)return null;
            return switch(safeQuestion){
                case "지진 발생 시 안전한 대피 방법" -> "지진 대피 행동요령";
                case "호우 발생 시 안전한 대피 방법" -> "호우 대피 행동요령";
                case "산불 발생 시 안전한 대피 방법" -> "산불 대피 행동요령";
                case "태풍 발생 시 안전한 대피 방법" -> "태풍 대피 행동요령";
                case "홍수 발생 시 안전한 대피 방법" -> "홍수 대피 행동요령";
                default -> safeQuestion;
            };
        }
        @Override
        public String toString() {
            return "PreparedQuestion[safeQuestion=<redacted>, approvedForOutbound=" + approvedForOutbound + "]";
        }
    }
}
