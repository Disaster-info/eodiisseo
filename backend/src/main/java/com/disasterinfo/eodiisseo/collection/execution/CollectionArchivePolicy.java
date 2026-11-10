package com.disasterinfo.eodiisseo.collection.execution;

import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Fail-closed raw retention: suspicious bodies are omitted, never silently called original bytes. */
public final class CollectionArchivePolicy {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Duration retention;private final String approval;private final Set<String> secrets;
    private static final Pattern SENSITIVE=Pattern.compile("(?i)(service.?key|auth.?key|api.?key|token|credential|private.?key|client.?secret|authorization|bearer\\s|password|signature|https?://[^\\s\\\"<>]+\\?)");
    public record Body(byte[] bytes,String sha256,boolean omitted) {
        public Body {bytes=bytes==null?null:bytes.clone();}
        @Override public byte[] bytes(){return bytes==null?null:bytes.clone();}
        @Override public String toString(){return "ArchiveBody[redacted]";}
    }
    public CollectionArchivePolicy(Duration retention,String approval,Set<String> secretValues) {
        this.retention=Objects.requireNonNull(retention);this.approval=Objects.requireNonNull(approval);this.secrets=Set.copyOf(secretValues);
        if(retention.isNegative()||retention.isZero()||approval.isBlank()||SENSITIVE.matcher(approval).find()||secrets.stream().anyMatch(String::isBlank)||secrets.stream().anyMatch(approval::contains))throw new IllegalArgumentException("Explicit private retention/access approval required");
    }
    public Duration retention(){return retention;}
    public String approval(){return approval;}
    private boolean sensitive(Object value,int depth){
        if(depth>32)return true;
        if(value instanceof String text)return SENSITIVE.matcher(text).find()||secrets.stream().anyMatch(text::contains);
        if(value instanceof Map<?,?> map)return map.entrySet().stream().anyMatch(e->sensitive(e.getKey(),depth+1)||sensitive(e.getValue(),depth+1));
        if(value instanceof List<?> list)return list.stream().anyMatch(e->sensitive(e,depth+1));
        return false;
    }
    public Body inspect(byte[] raw){
        Objects.requireNonNull(raw);if(raw.length>2_097_152)throw new IllegalArgumentException("Body too large");
        boolean omit;
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            omit=SENSITIVE.matcher(text).find()||secrets.stream().anyMatch(text::contains);
            String stripped=text.stripLeading();
            if(!omit&&(stripped.startsWith("{")||stripped.startsWith("[")||stripped.startsWith("\""))) {
                try{omit=sensitive(JSON.readValue(text,Object.class),0);}catch(RuntimeException malformed){omit=true;}
            }
        }catch(CharacterCodingException invalid){omit=true;}
        try{return new Body(omit?null:raw,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)),omit);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
}