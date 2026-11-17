package com.disasterinfo.eodiisseo.collection.operations;
import java.time.Instant;
import java.util.*;
/** Trusted listener binding for THIS request (e.g. verified mutual TLS leaf certificate).
 * No default provider; a worker-supplied fingerprint is not peer authentication. */
public interface CollectionCommandPeerPort {
 record Peer(UUID worker,String certificateSha256,Instant until,boolean synthetic){
  public Peer{Objects.requireNonNull(worker);Objects.requireNonNull(until);if(certificateSha256==null||!certificateSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Pinned peer required");}
  @Override public String toString(){return "CommandPeer[redacted]";}
 }
 Peer requireVerifiedPeer();
}
