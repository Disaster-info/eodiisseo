package com.disasterinfo.eodiisseo.ai.chat.port;
import java.util.List;
import java.util.function.Consumer;
/** Short local completion transaction only. Never invoke embedding/search/model transport inside this callback. */
public interface ChatEvidenceCommitPort {
    void withCurrentEvidence(List<ChatOfficialResourceSearchPort.OfficialResource> expected,Consumer<Boolean> persist);
}