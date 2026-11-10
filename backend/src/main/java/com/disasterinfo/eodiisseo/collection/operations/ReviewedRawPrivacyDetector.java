package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.execution.CollectionRawPrivacyPort;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;
/** Conservative tripwires plus explicit reviewed public-text fields. Not a complete PII classifier. XML/unknown text needs review. */
public final class ReviewedRawPrivacyDetector implements CollectionRawPrivacyPort {
    private static final Pattern PII=Pattern.compile("(?i)([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)(?:01[016789]|02)[ -]?\\d{3,4}[ -]?\\d{4}(?!\\d)|(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d))");
    private final Set<String> fields;private final String review;
    public ReviewedRawPrivacyDetector(Set<String> reviewedPublicTextFields,String reviewFingerprint){fields=Set.copyOf(reviewedPublicTextFields);if(fields.stream().anyMatch(f->!f.matches("[A-Za-z0-9_]{1,100}"))||!reviewFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Explicit reviewed privacy profile required");review=reviewFingerprint;}
    public String fingerprint(){return CollectionAssurance.hash("COLLECTION_PRIVACY_V1|"+new TreeSet<>(fields)+"|"+PII.pattern());}
    public String reviewFingerprint(){return review;}
    public Decision inspect(String text){if(PII.matcher(text).find())return Decision.OMIT;try{return inspect(JsonMapper.builder().build().readValue(text,Object.class),"",0);}catch(RuntimeException invalid){return Decision.REVIEW_REQUIRED;}}
    private Decision inspect(Object value,String field,int depth){
        if(depth>32)return Decision.REVIEW_REQUIRED;
        if(value instanceof Map<?,?> map){for(var e:map.entrySet()){var result=inspect(e.getValue(),e.getKey().toString(),depth+1);if(result!=Decision.ALLOW)return result;}return Decision.ALLOW;}
        if(value instanceof List<?> list){for(var x:list){var result=inspect(x,field,depth+1);if(result!=Decision.ALLOW)return result;}return Decision.ALLOW;}
        if(value instanceof String s){if(PII.matcher(s).find())return Decision.OMIT;return fields.contains(field)||s.matches("-?[0-9]+(?:\\.[0-9]+)?")?Decision.ALLOW:Decision.REVIEW_REQUIRED;}
        return value==null||value instanceof Number||value instanceof Boolean?Decision.ALLOW:Decision.REVIEW_REQUIRED;
    }
}