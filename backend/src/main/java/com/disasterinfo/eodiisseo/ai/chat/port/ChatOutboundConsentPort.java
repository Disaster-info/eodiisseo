package com.disasterinfo.eodiisseo.ai.chat.port;
import java.util.UUID;
/** Per-member external AI processing consent. Missing consent always denies. */
public interface ChatOutboundConsentPort { boolean permits(UUID memberId); default boolean localPersistenceRevoked(UUID memberId){return false;} }
