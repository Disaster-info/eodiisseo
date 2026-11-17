package com.disasterinfo.eodiisseo.collection.operations;
import java.util.Optional;
/** Current, locally available signed DATABASE review for this exact isolated LOGIN plane.
 * Empty means missing/revoked. No network, credentials, generated approvals or test default.
 * gateway_sha256 binds login_policy_hash(plane,worker,ops); existing acl_sha256 binds the public inspector.
 * This prepares a plane only: never an API, cutover or collection execution approval. */
@FunctionalInterface
public interface CollectionLoginReviewPort {
    Optional<CollectionAssurance.Artifact> current();
}
