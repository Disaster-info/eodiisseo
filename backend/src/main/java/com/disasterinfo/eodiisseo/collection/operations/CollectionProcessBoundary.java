package com.disasterinfo.eodiisseo.collection.operations;
import java.time.*;
import java.util.*;
/** Local supervisor inventory, not self-reported process booleans. No process launch or RPC endpoint. */
public final class CollectionProcessBoundary {
 public record Process(long pid,CollectionLoginPlane.Kind plane,String principal,String executableHash,Set<String> credentialRoles) {
  public Process{credentialRoles=Set.copyOf(credentialRoles);if(pid<1||principal==null||principal.isBlank()||executableHash==null||!executableHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact supervisor process identity required");}
 }
 public record Snapshot(Set<Process> processes,Instant observedAt,Instant until,boolean complete,boolean synthetic){public Snapshot{processes=Set.copyOf(processes);}}
 public static List<String> blockers(Snapshot s,Map<CollectionLoginPlane.Kind,String> roles,Instant now,boolean production){
  if(s==null||!s.complete()||s.observedAt()==null||s.until()==null||s.observedAt().isAfter(now)||!s.until().isAfter(now)||production&&s.synthetic())return List.of("FRESH_AUTHORITATIVE_PROCESS_INVENTORY_REQUIRED");
  if(roles.size()!=3||new HashSet<>(roles.values()).size()!=3)return List.of("DISTINCT_DATABASE_ROLES_REQUIRED");
  var reasons=new ArrayList<String>();var pids=new HashSet<Long>();var principals=new HashSet<String>();
  for(var kind:CollectionLoginPlane.Kind.values()){
   var found=s.processes().stream().filter(p->p.plane()==kind).toList();
   if(found.size()!=1||!roles.containsKey(kind)){reasons.add("EXACT_PROCESS_ROLE_BINDING_REQUIRED");continue;}
   var p=found.getFirst();if(!pids.add(p.pid())||!principals.add(p.principal())||!p.credentialRoles().equals(Set.of(roles.get(kind))))reasons.add("PROCESS_OR_CREDENTIAL_PLANE_MIXED");
  }
  if(s.processes().size()!=3)reasons.add("UNKNOWN_EXECUTION_PRINCIPAL");return List.copyOf(reasons);
 }
 private CollectionProcessBoundary(){}
}
