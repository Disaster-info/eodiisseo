package com.disasterinfo.eodiisseo.collection.execution;

import java.time.*;
import java.util.*;

/** No default transport, credentials, endpoint fallback, Spring bean or scheduler registration. */
public interface CollectionProviderPort {
    record Request(LocalDate from, LocalDate to, int page, int rows, Duration timeout) {
        public Request {
            Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(timeout);
            if(to.isBefore(from)||to.isAfter(from.plusDays(2))||page<1||page>100||rows<1||rows>100||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(15))>0)
                throw new IllegalArgumentException("Bounded request required");
        }
    }
    record Reply(int status,byte[] body,Duration retryAfter) {
        public Reply {body=Objects.requireNonNull(body).clone();Objects.requireNonNull(retryAfter);if(status<100||status>599||body.length>2_097_152||retryAfter.isNegative())throw new IllegalArgumentException("Invalid response");}
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "CollectionReply[status="+status+", body=redacted]";}
    }
    record Page(int number,int total,List<Map<String,Object>> items) {
        public Page {items=List.copyOf(items);}
        @Override public String toString(){return "CollectionPage[number="+number+", total="+total+", items=redacted]";}
    }
    /** These are externally reviewed guarantees, not observations inferred from one response. */
    record Evidence(boolean boundedRange,boolean stableSnapshot,boolean emptyResult,boolean incremental,String reference) {
        public Evidence {Objects.requireNonNull(reference);if(reference.isBlank())throw new IllegalArgumentException("Evidence reference required");}
        public static Evidence unverified(){return new Evidence(false,false,false,false,"CONTRACT_REQUIRED");}
    }
    final class InvalidResponse extends RuntimeException {
        private final String code;
        public InvalidResponse(String code){super("Provider response validation failed");this.code=code;}
        public String code(){return code;}
    }
    default void validateRequest(Request request) {}
    String contractKey();
    Evidence evidence();
    Reply exchange(Request request);
    Page decode(Reply reply,Request request);
    String identity(Map<String,Object> item);
    default boolean executable(){return true;}
}