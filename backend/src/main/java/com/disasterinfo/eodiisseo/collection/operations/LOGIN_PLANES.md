# Collection 4C-2: isolated LOGIN preparation

This is preparation, not production execution authorization. Existing Lexical/RAG, official
admission blockers, legacy schedulers and PreparedCollectionRuntime.collect remain unchanged.

## Composition and credential boundary

Run Worker, trusted DB Gateway and OPS as **separate processes and deployment identities**.
Each process receives only its own credential and owns one CollectionLoginPool, JdbcTemplate,
EntityManagerFactory and JpaTransactionManager. No common credentials map, parent context,
@Primary routing DataSource, per-request credentials or pool unwrap is provided.
Separate Spring contexts in one JVM demonstrate wiring; they are not protection against hostile
code, reflection, debuggers, process-memory access or an operating-system administrator.

Migration uses a separate short-lived LOGIN, outside runtime contexts. PostGIS installation
requires the isolated bootstrap administrator; runtime roles never receive that privilege.
Definer MUST remain NOLOGIN, non-superuser and non-inheritable. A LOGIN Definer would violate
the existing SECURITY DEFINER boundary. Runtime roles must have no membership in it or Migration.

The pool does not expose credential getters or its Hikari delegate. PostgreSQL DISCARD ALL
runs on checkout before any transaction; Hikari JDBC state reset alone does not clear GUCs.
Server prepared statements are disabled for this pool because DISCARD ALL invalidates them.
Never enable credential-bearing DataSource debug dumps, SQL parameter tracing, heap dumps or
remote JMX in the runtime deployment. Temporary test credentials are generated only in memory.
For a remote production database, TLS and credential provisioning require deployment review;
this stage validates loopback disposable PostgreSQL only.

## Explicit Spring configuration (default OFF)

Import CollectionLoginPlaneConfiguration into the appropriate isolated root. Enabling
`eodiisseo.collection.login-plane.enabled=true` requires an exact `kind` WORKER/GATEWAY/OPS
and external reviewed bindings. It does not construct credentials or start a worker.
Supply one CollectionLoginPlane, one private CollectionLoginPool DataSource, and one EMF/TM
pair using that exact DataSource. Supply JdbcTemplate over it and pinned review public keys.
Do not mix the old unqualified admin-enabled composition with this OPS root.

A CollectionLoginReviewPort must return a current, revocable, signed DATABASE Artifact.
The existing CollectionAssurance verifier and CollectionDatabasePrivilegeInspector are reused:
- `acl_sha256`: current public-schema privilege-inspector fingerprint;
- `gateway_sha256`: collection_production.login_policy_hash(kind, worker, ops), binding private gateway
  policy, DB name, exact kind and authenticated session_user;
- `barrier_version`: 2026111301; all 1001..1006, 1301 and 1302 must be successfully installed;
- worker/OPS/migration/schema-owner names must match the reviewed actual privilege policy.
No test signing keys or synthetic approvals are supplied as runtime beans. A valid LOGIN
review does not approve API evidence, Projection, quota, Fleet exclusion or cutover.
The local review provider must not perform network I/O inside a gateway/OPS transaction.
Missing/expired/revoked/invalid review, altered ACL or wrong pool fails startup or subsequent
Gateway/OPS calls. Review is checked before and within the short transaction and again before
completion. SQL still performs its original deferred COMMIT authorization and locking.
An independently revoked external review cannot be atomically serialized with PostgreSQL COMMIT;
production revocation must also revoke the persisted worker/barrier approval using existing
OPS control functions. External review freshness alone is not a distributed commit protocol.

Gateway assembly creates ProductionCollectionWriteGateway only. It verifies shared JPA/JDBC
backend PID on every write. OPS assembly creates the existing CollectionOperationsService and
ProductionCollectionCutover using the same control-plane DataSource as JdbcCollectionAssurance
and VerifiedCollectionOperationalEvidence. OPS evidence/trust/Fleet/Sender bindings remain
explicit. A Worker root rejects privileged Gateway/OPS objects and extra credential pools.

## Additive optional Migration 1302

The original 1001..1006 and 1301 are unchanged. 1302 adds only read-only LOGIN attestation/hash
and authorize_ops functions, with fixed search_path and PUBLIC EXECUTE revoked. No role,
password, installation, approval, grant, scheduler or execution is seeded. Reviewed bootstrap
must transfer the new SECURITY DEFINER functions to the existing NOLOGIN Definer and grant:
- verify_login_plane(text), login_policy_hash(text,name,name): reviewed Worker/Gateway/OPS only;
- authorize_ops(uuid,uuid): OPS only.
Keep the existing restricted 1301 function grants; do not grant all functions to runtime roles.

OPS authenticate/lock uses the existing member -> session -> OPS-role Definer lock ordering.
It needs only member_id/status SELECT on member; session_id/member_id/expires_at/revoked_at
SELECT on auth_session; plus approved administrative metadata including Flyway history.
No auth table DML or refresh token SELECT is needed. PostGIS default PUBLIC SELECT on
geography_columns/geometry_columns/spatial_ref_sys also violates the existing strict Worker
read policy. The disposable bootstrap revokes these PUBLIC grants only in its own DB and
retains explicit needed grants. Production requires DBA impact review and scoped grants to
existing applications; Migration 1302 does not silently change extension ACLs. The Definer retains the already reviewed
row-lock grants. Broader resolution/assurance metadata grants need explicit deployment review;
they are not auto-granted. Updating approval metadata invalidates the reviewed policy snapshot,
so onboarding new evidence requires a coordinated review refresh rather than silently accepting it.

## What tests do and do not prove

CollectionProductionLoginPostgresTest repeats the existing 4C-1 scenarios with real SCRAM
LOGINs and separate pools, including a wrong-password 28P01 assertion (trust is not success).
It also covers Boot context wiring, worker isolation, OPS ACTIVE session control without auth
DML, pool cleanup, signed-review withdrawal, transaction rollback and a distinct Migration LOGIN
applying/validating all optional migrations on a fresh disposable DB. Definer remains NOLOGIN.

All data, review keys and approvals are test fixtures confined to collection4c_UUID on 55433.
Pools close before owned DB/roles are dropped. No actual external sender is called. These tests
are neither real-provider validation nor production deployment approval. Actual process secret
injection, remote DB TLS, real signed evidence/revocation storage, real Fleet exclusion and all
seven official API admissions still block production cutover.
