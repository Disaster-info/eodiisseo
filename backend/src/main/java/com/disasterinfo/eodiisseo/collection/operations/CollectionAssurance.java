package com.disasterinfo.eodiisseo.collection.operations;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Detached reviewer signatures bind exact typed metadata, scope, expiry and synthetic provenance.
 * A valid internal review signature does not prove an unresolved external API contract. */
public final class CollectionAssurance {
    public enum Kind { FLEET,SENDER,DATABASE,ARCHIVE,DOMAIN_POLICY,API,PROJECTION,QUOTA,HTTP_COMPLETION }
    public record Artifact(UUID id,Kind kind,String scope,String version,String issuer,String reference,
                           Map<String,Object> payload,Instant from,Instant until,boolean synthetic,byte[] signature){
        public Artifact {
            Objects.requireNonNull(id);Objects.requireNonNull(kind);Objects.requireNonNull(from);Objects.requireNonNull(until);
            // Internal signed-review envelope v1: UTC instants at PostgreSQL microsecond precision.
            from=from.truncatedTo(java.time.temporal.ChronoUnit.MICROS);until=until.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(scope==null||!scope.matches("[A-Z0-9_.:-]{1,120}")||issuer==null||!issuer.matches("[A-Z0-9_.:-]{1,100}")
                ||version==null||version.isBlank()||version.length()>100||!until.isAfter(from))throw new IllegalArgumentException("Versioned scoped review required");
            CollectionOperationsService.reference(reference);payload=Map.copyOf(payload);validate(kind,payload);
            signature=signature==null?new byte[0]:signature.clone();
        }
        @Override public byte[] signature(){return signature.clone();}
        @Override public String toString(){return "AssuranceArtifact[id="+id+",kind="+kind+",payload=redacted]";}
        public String digest(){return hash(canonical(payload));}
        public byte[] signingBytes(){return canonical(Map.ofEntries(Map.entry("id",id.toString()),Map.entry("kind",kind.name()),Map.entry("scope",scope),Map.entry("version",version),Map.entry("issuer",issuer),Map.entry("reference",reference),Map.entry("digest",digest()),Map.entry("from",from.toString()),Map.entry("until",until.toString()),Map.entry("synthetic",synthetic))).getBytes(StandardCharsets.UTF_8);}
    }
    public record TrustRoot(PublicKey key,Set<Kind> kinds,boolean synthetic){public TrustRoot{Objects.requireNonNull(key);kinds=Set.copyOf(kinds);}}
    public static final class Verifier {
        private final Map<String,TrustRoot> roots;
        public Verifier(Map<String,TrustRoot> roots){this.roots=Map.copyOf(roots);}
        public boolean verify(Artifact a,CollectionOperationMode mode,Instant now){
            var root=roots.get(a.issuer());if(root==null||!root.kinds().contains(a.kind())||a.from().isAfter(now)||!a.until().isAfter(now)
                ||(mode==CollectionOperationMode.PRODUCTION&&(root.synthetic()||a.synthetic()))||a.signature().length!=64)return false;
            try{var s=Signature.getInstance("Ed25519");s.initVerify(root.key());s.update(a.signingBytes());return s.verify(a.signature());}catch(GeneralSecurityException invalid){return false;}
        }
    }
    private static final Map<Kind,Set<String>> FIELDS=Map.of(
        Kind.FLEET,Set.of("inventory_sha256","deployment_sha256","restart_policy_sha256","pending_requests_sha256","stopped","restart_blocked","schedulers_disabled","complete_inventory","instance_ids","observation_ttl_seconds"),
        Kind.SENDER,Set.of("implementation_sha256","runtime_sha256","endpoint_sha256","one_attempt","no_redirect","no_proxy","no_secret_logging","bounded_body","bounded_timeout"),
        Kind.DATABASE,Set.of("worker_role","ops_role","migration_role","schema_owner","acl_sha256","barrier_version","gateway_sha256"),
        Kind.ARCHIVE,Set.of("retention_seconds","acl_sha256","privacy_review_sha256","secret_detector_sha256","expiry_plan_sha256","audit_retention_sha256","receipt_fk_plan_sha256"),
        Kind.DOMAIN_POLICY,Set.of("manifest_sha256","normalizer_sha256","verification_rules_sha256"),
        Kind.API,Set.of("document_sha256","response_contract_sha256","route_sha256","bounded_range","stable_snapshot","incremental","empty_result"),
        Kind.PROJECTION,Set.of("contract_sha256","normalizer_sha256","mapping_sha256","initial_date","canonical_source","incident_link_verified","coverage_allowed","empty_allowed"),
        Kind.QUOTA,Set.of("quota_scope_id","starts_at","ends_at","request_limit","min_interval_ms","provider_quota_sha256"),
        Kind.HTTP_COMPLETION,Set.of("run_id","snapshot_sha256","request_inventory_sha256","no_active_request"));
    static void validate(Kind kind,Map<String,Object> p){
        if(!p.keySet().equals(FIELDS.get(kind)))throw new IllegalArgumentException("Exact typed evidence fields required");
        for(var e:p.entrySet()){
            String k=e.getKey();Object v=e.getValue();
            if(k.endsWith("sha256")){if(!(v instanceof String s)||!s.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Evidence digest required");}
            else if(k.equals("retention_seconds")||k.equals("observation_ttl_seconds")){if(!(v instanceof Number n)||n.longValue()<1||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Explicit retention approval required");Duration.ofSeconds(n.longValue()).toMillis();}
            else if(k.equals("instance_ids")){if(!(v instanceof List<?> list)||list.isEmpty()||list.stream().anyMatch(id->!(id instanceof String text)||!text.matches("[A-Za-z0-9_.:-]{1,120}"))||new HashSet<>(list).size()!=list.size())throw new IllegalArgumentException("Exact fleet inventory required");}
            else if(k.equals("request_limit")||k.equals("min_interval_ms")){if(!(v instanceof Number n)||n.longValue()<(k.equals("request_limit")?1:0)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Explicit quota numeric contract required");}
            else if(k.equals("starts_at")||k.equals("ends_at")){Instant.parse((String)v);}
            else if(k.equals("quota_scope_id")){if(!(v instanceof String s)||!s.matches("[A-Za-z0-9_.:-]{1,200}"))throw new IllegalArgumentException("Opaque quota identity required");}
            else if(k.endsWith("role")||k.equals("schema_owner")){if(!(v instanceof String s)||!s.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Exact DB identity required");}
            else if(k.equals("barrier_version")){if(!(v instanceof String s)||!s.matches("[0-9]{10}"))throw new IllegalArgumentException("Barrier version required");}
            else if(k.equals("initial_date")){LocalDate.parse((String)v);}
            else if(k.equals("canonical_source")||k.equals("run_id")){UUID.fromString((String)v);}
            else if(!(v instanceof Boolean))throw new IllegalArgumentException("Explicit evidence decision required");
        }
    }
    private static Object sorted(Object x){if(x instanceof Map<?,?> m){var t=new TreeMap<String,Object>();m.forEach((k,v)->t.put(k.toString(),sorted(v)));return t;}if(x instanceof List<?> l)return l.stream().map(CollectionAssurance::sorted).toList();return x;}
    public static String canonical(Object x){return JsonMapper.builder().build().writeValueAsString(sorted(x));}
    public static String hash(String x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException bad){throw new IllegalStateException(bad);}}
}