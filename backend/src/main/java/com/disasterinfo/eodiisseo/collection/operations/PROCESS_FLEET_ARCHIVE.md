# Collection 4C-3 preparation boundaries

Production execution remains disabled. This stage provides no arming, Worker/Scheduler swap,
actual public API, AI, FCM, OpenStack deployment or operating-system account provisioning.
Existing migrations 1001..1006 and 1301..1302 remain unchanged.

## Process identity and credentials

CollectionProcessBoundary validates a COMPLETE authoritative supervisor inventory: exactly one
Worker/Gateway/OPS process, distinct PID, OS security principal and DB role, exact credential-role
scope, executable hash, fresh validity and non-synthetic provenance in production. It does not
turn a self-reported record into supervisor evidence. Production still requires reviewed supervisor
observation, signing, revocation and a fencing mechanism valid through the DB commit boundary.
Different pools or PIDs under the same OS account are not hostile-process isolation.

Tests create three actual child JVMs, pass only that role's temporary password via a private stdin
pipe, clear inherited environment and use fixed metadata/denial operations. Child logs are disabled
and output is continuously drained and restricted to a bounded non-secret result. All JVMs currently
share the Windows account, so the production process policy rejects their inventory. Tests neither
create OS accounts nor publish their credentials. A real deployment needs separate OS/container
identities, per-service secret mounts, filesystem ACLs, debugging/ptrace/proc restrictions and reviewed
launcher/restart policy. Migration runs outside runtime processes; Definer remains NOLOGIN.

CollectionWorkerCommandBoundary accepts a bounded signed HEARTBEAT envelope only, never SQL,
projection payloads, a callback or a Gateway credential. It requires an authoritative signed-caller
binding and a durable same-DataSource command journal inside the existing Gateway transaction.
No network listener, automatic Bean, default authorizer or in-memory production journal is provided.
Channel authentication, pinned peer identities, durable replay semantics and a deployed broker are
explicit blockers. The existing in-process Gateway callback is NOT exposed as RPC. Moving domain
Projection to another process is blocked until its JPA/JDBC atomicity can remain within the Gateway.

## PostgreSQL TLS and credential leases

CollectionLoginPool has explicit TLS configuration via CollectionTlsPolicy: verify-full, a pinned
public CA file/digest/validity, no custom socket factory or insecure fallback. Cleartext is restricted to the exact approved 55433 collection4c_UUID audit database.
All other URLs require explicit verified TLS. A caller supplies an explicit credential lease and generation; checkout rejects
missing/expired/replaced credentials and trust changes, including already cached pool connections.
It never discovers other roles' credentials. In-flight authorization still uses existing SQL generation,
lease, OPS session and deferred COMMIT checks. Rotating a password does not revoke an already open
PostgreSQL session; real credential rotation must also revoke its persisted execution/session scope.

The Windows test script prepares a NEW Postgres17 instance on loopback55435 with hostssl SCRAM,
hostnossl reject, max20 connections/shared_buffers32MB, a temporary CA/server certificate and no
statement/parameter logging. It never changes55433, system trust stores or production certificates.
Only installed PostgreSQL/OpenSSL binaries are used; no downloads. Wrong CA and hostname,
credential expiry/generation and real separate LOGIN roles are tested. TLS root/server private keys
and bootstrap credentials remain in the private disposable TEMP root until cleanup; never commit it.
Stop-TlsAudit checks the exact TEMP UUID path and postmaster executable before stopping/removing it.

## Fleet evidence and commit boundary

VerifiedCollectionOperationalEvidence reuses CollectionAssurance signatures and the existing
CollectionFleetEvidencePort. withStableFleet checks signed evidence and local inventory before and
after short DB work in the same transaction, rolling back on changes, missing instances or expiry.
Existing signed-review revocation remains serialized by the DB gate/row locks. This is NOT atomic
with a remote supervisor: a stop/restart after the final local observation can race COMMIT.
PROCESS_PRINCIPAL_ISOLATION_AND_FLEET_COMMIT_FENCE_REQUIRED therefore remains an unconditional
production blocker. No arbitrary boolean/environment flag can satisfy it. A future supervisor lease
must prevent/revoke restart and bind exact inventory/deployment/restart/pending-request fingerprints
through commit; real supervisor evidence cannot be obtained in this task without deployment access.

## Archive holds and audit

CollectionArchiveLifecycle uses actual recorded expiry and receipt/audit references via the same
DataSource as approval evidence. Missing/malformed/unverified policy yields HOLD, never deletion.
It does not reconstruct fetch time, rewrite original bytes, invent retention or call a redacted body
an original. Existing CollectionArchivePolicy omits secret/PII/unknown bytes into quarantine with
hash/metadata; reviewed field policy and access/retention approval remain mandatory.

Optional V2026111501 adds only immutable archive_hold_audit and an OPS-authenticated HOLD function.
It seeds no role/credential/grant/approval/retention/deletion and changes no existing migration.
Audit retains an actor UUID digest, opaque reason, optional evidence reference and original archive FK.
Digesting an actor ID is pseudonymization, not a legal erasure guarantee. The FK prevents deletion of
referenced archive/evidence. The ALWAYS immutable trigger participates in the production fingerprint.
No UPDATE/DELETE/automatic cleanup or erasure function exists. Only the reviewed OPS role may be
granted execute on record_archive_hold; the function owner must be the existing NOLOGIN Definer.
Runtime roles get no direct table DML. Wrong role/session, duplicate decision, mutable history or
unapproved free-text reasons fail. The table does not cascade from a member/session deletion.
Evidence's existing indirect member references still require a reviewed privacy/erasure protocol.

Body retention, immutable receipts, audit retention, legal holds, retry/revocation and data-subject
rights require a signed policy and separately approved split/tombstone/erasure design. The existing
ARCHIVE_ERASURE_AND_AUDIT_RETENTION_CONTRACT_REQUIRED blocker stays. Automatic deletion and
approval remain prohibited. Retention expiry alone is not deletion permission.

## Validation separation

The TLS instance proves actual TLS/SCRAM, CA/hostname/password rejection and isolated child JVM
connections. Production Gateway commit/fencing regressions run on the existing approved55433
cluster. Their existing immutable SQL audit contract allows synthetic evidence only there. The
TLS55435 fixture is NOT used to weaken that guard or claim a TLS domain-write/Projection cutover.
A reviewed full Gateway-over-TLS rehearsal remains a 4C-4 prerequisite.

## 4C-4 entry conditions

Reviewed separate process identities and secret delivery; real supervisor inventory/restart fencing;
authenticated typed broker and durable command journal; Gateway-local Projection transaction proof;
real TLS deployment/rotation and emergency persisted revocation; Archive ACL/retention/erasure and
member-deletion policy; fresh signed approvals and all seven unresolved official API contracts.
Test certificates, synthetic signed artifacts and same-account JVM probes cannot satisfy these.
