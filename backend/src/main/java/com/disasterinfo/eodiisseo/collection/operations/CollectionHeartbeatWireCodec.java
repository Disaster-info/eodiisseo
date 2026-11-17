package com.disasterinfo.eodiisseo.collection.operations;
import java.nio.*;import java.nio.charset.*;import java.util.*;import java.time.*;
/** Bounded HEARTBEAT only. No command string, SQL, callback, credentials or permissive fields. */
public final class CollectionHeartbeatWireCodec {
 public static final int MAX_BYTES=4096;
 private static final Set<String> FIELDS=Set.of("command","worker","source","run","claim","fence","generation","until","signature");
 public static byte[] encode(CollectionWorkerCommandBoundary.Heartbeat r){var m=new LinkedHashMap<String,Object>();m.put("command",r.command().toString());m.put("worker",r.worker().toString());m.put("source",r.source().toString());m.put("run",r.run().toString());m.put("claim",r.claim().toString());m.put("fence",r.fence());m.put("generation",r.generation());m.put("until",r.until().toString());m.put("signature",Base64.getEncoder().encodeToString(r.signature()));return CollectionAssurance.canonical(m).getBytes(StandardCharsets.UTF_8);}
 private static long integer(Object o){if(!(o instanceof Integer||o instanceof Long))throw new IllegalArgumentException();return ((Number)o).longValue();}
 public static CollectionWorkerCommandBoundary.Heartbeat decode(byte[] bytes){
  try{if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException();String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
   var m=tools.jackson.databind.json.JsonMapper.builder().build().readValue(text,Map.class);if(!m.keySet().equals(FIELDS))throw new IllegalArgumentException();
   return new CollectionWorkerCommandBoundary.Heartbeat(UUID.fromString((String)m.get("command")),UUID.fromString((String)m.get("worker")),UUID.fromString((String)m.get("source")),UUID.fromString((String)m.get("run")),UUID.fromString((String)m.get("claim")),integer(m.get("fence")),integer(m.get("generation")),Instant.parse((String)m.get("until")),Base64.getDecoder().decode((String)m.get("signature")));
  }catch(Exception denied){throw new IllegalArgumentException("Invalid bounded heartbeat envelope");}
 }
 private CollectionHeartbeatWireCodec(){}
}
