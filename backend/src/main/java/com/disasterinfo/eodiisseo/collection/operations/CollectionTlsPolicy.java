package com.disasterinfo.eodiisseo.collection.operations;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
/** Pinned public CA, no custom trust factory, insecure mode or credential discovery. */
public record CollectionTlsPolicy(Path rootCertificate,String sha256,Instant validUntil) {
 public CollectionTlsPolicy {
  Objects.requireNonNull(rootCertificate);Objects.requireNonNull(validUntil);
  if(!rootCertificate.isAbsolute()||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Pinned public CA required");
 }
 public void verify(){
  try{
   if(!validUntil.isAfter(Instant.now())||!Files.isRegularFile(rootCertificate)||!sha256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(rootCertificate)))))throw new IllegalStateException();
   try(var in=Files.newInputStream(rootCertificate)){((java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in)).checkValidity();}
  }catch(Exception rejected){throw new IllegalStateException("TLS trust policy missing, changed or expired");}
 }
 @Override public String toString(){return "CollectionTlsPolicy[redacted]";}
}
