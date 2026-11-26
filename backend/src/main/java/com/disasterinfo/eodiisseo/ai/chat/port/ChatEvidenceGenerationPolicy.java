package com.disasterinfo.eodiisseo.ai.chat.port;
/** A search strategy can require an inseparable generation policy, even if only its search port is injected. */
public interface ChatEvidenceGenerationPolicy extends ChatOfficialResourceSearchPort {
    ChatAnswerGenerationPort guardGeneration(ChatAnswerGenerationPort delegate);

    default SearchResult recheck(String question, SearchResult evidence) {
        return search(question);
    }
}
