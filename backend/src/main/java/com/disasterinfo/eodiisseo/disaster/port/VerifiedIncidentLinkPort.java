package com.disasterinfo.eodiisseo.disaster.port;

import com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted internal contract, NOT a field-name guesser or a human approval bypass.
 * An implementation must verify provider incident ID, announcement identity, exact content hash,
 * authoritative monotonic revision order, issue time and explicit incident state against official evidence.
 * No production implementation is registered until the provider contract is obtained.
 */
public interface VerifiedIncidentLinkPort {
    Optional<Link> resolve(UUID source,Provider provider,String announcement,String contentSha256);
    record Link(UUID source,Provider provider,String announcement,String contentSha256,String incidentId,
                long officialSequence,Instant issuedAt,Instant occurredAt,State state,Instant endedAt,
                URI evidenceUrl,String contractVersion) {}
    enum State { OCCURRED, ONGOING, ENDED }
}
