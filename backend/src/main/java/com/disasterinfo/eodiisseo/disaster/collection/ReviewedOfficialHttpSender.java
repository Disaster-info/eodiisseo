package com.disasterinfo.eodiisseo.disaster.collection;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort;
import com.disasterinfo.eodiisseo.collection.operations.*;
import javax.sql.DataSource;
import java.net.URI;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;

/** No automatic construction. Reuses bounded OfficialHttp only with verified JVM startup retry controls. */
public final class ReviewedOfficialHttpSender implements OfficialCollectionHttpTransport.Get,AutoCloseable {
    public static final Duration NO_AUTOMATIC_RETRY=Duration.ofSeconds(Long.MAX_VALUE);
    private final BiFunction<URI,Duration,OfficialHttp.HeadersReply> exchange;private final OfficialHttp owned;private final Clock clock;
    private final boolean synthetic;private final String runtimeFingerprint;private boolean closed;
    private ReviewedOfficialHttpSender(BiFunction<URI,Duration,OfficialHttp.HeadersReply> exchange,OfficialHttp owned,Clock clock,boolean synthetic,String fingerprint){this.exchange=exchange;this.owned=owned;this.clock=clock;this.synthetic=synthetic;this.runtimeFingerprint=fingerprint;}
    public static ReviewedOfficialHttpSender production(){
        String fingerprint=verifyRuntime(ManagementFactory.getRuntimeMXBean().getInputArguments(),System.getProperties(),Runtime.version().feature());
        var http=new OfficialHttp();return new ReviewedOfficialHttpSender(http::getWithHeaders,http,Clock.systemUTC(),false,fingerprint);
    }
    /** Only fake exchanges in a verified disposable DB; never an alternate production constructor. */
    public static ReviewedOfficialHttpSender audit(BiFunction<URI,Duration,OfficialHttp.HeadersReply> fake,Clock clock,DataSource ds,CollectionOperationMode mode){
        if(mode!=CollectionOperationMode.DISPOSABLE_AUDIT)throw new IllegalArgumentException("Synthetic sender cannot run in production");mode.verify(ds);return new ReviewedOfficialHttpSender(Objects.requireNonNull(fake),null,clock,true,CollectionAssurance.hash("SYNTHETIC sender runtime"));
    }
    static String verifyRuntime(List<String> args,Properties p,int feature){
        Map<String,String> required=Map.of("jdk.httpclient.redirects.retrylimit","1","jdk.httpclient.disableRetryConnect","true","jdk.httpclient.enableAllMethodRetry","false");
        if(feature!=21)throw new IllegalStateException("Reviewed JDK retry implementation required");
        for(var e:required.entrySet())if(args.stream().filter(a->a.startsWith("-D"+e.getKey()+"=")).count()!=1||!args.contains("-D"+e.getKey()+"="+e.getValue())||!e.getValue().equals(p.getProperty(e.getKey())))throw new IllegalStateException("HTTP startup retry guard required");
        for(String key:List.of("jdk.httpclient.HttpClient.log","javax.net.debug"))if(!p.getProperty(key,"").isBlank())throw new IllegalStateException("Secret-bearing network debugging must be disabled");
        return CollectionAssurance.hash("JDK21|"+p.getProperty("java.runtime.version","")+"|"+p.getProperty("java.vendor","")+"|"+CollectionAssurance.canonical(required));
    }
    public boolean synthetic(){return synthetic;}public String runtimeFingerprint(){return runtimeFingerprint;}
    public String implementationFingerprint(){
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");for(String file:List.of("ReviewedOfficialHttpSender.class","OfficialHttp.class","OfficialHttp$LimitedBody.class","OfficialCollectionHttpTransport.class"))try(var in=ReviewedOfficialHttpSender.class.getResourceAsStream(file)){byte[] bytes=Objects.requireNonNull(in).readAllBytes();digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}catch(Exception fail){throw new IllegalStateException("Sender build fingerprint unavailable");}
    }
    public static String endpointFingerprint(){return OfficialCollectionHttpTransport.endpointFingerprint();}
    @Override public CollectionProviderPort.Reply get(URI uri,Duration timeout){
        var r=observe(uri,timeout);return new CollectionProviderPort.Reply(r.status(),r.body(),retryAfter(r.status(),r.retryAfter(),clock.instant()));
    }
    /** Metadata observation reuses exactly the same endpoint, timeout, JDK retry and body guards. */
    public OfficialHttp.HeadersReply observe(URI uri,Duration timeout){
        if(closed||!OfficialCollectionHttpTransport.approvedEndpoint(uri)||timeout==null||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(15))>0)throw new CollectionFailure("SENDER_POLICY_REJECTED");
        if(!synthetic)verifyRuntime(ManagementFactory.getRuntimeMXBean().getInputArguments(),System.getProperties(),Runtime.version().feature());
        try{var r=exchange.apply(uri,timeout);if(r.status()<100||r.status()>599||r.body().length>2_097_152)throw new CollectionFailure("RESPONSE_LIMIT");return r;}
        catch(RuntimeException failed){throw new CollectionFailure("TRANSPORT");}
    }
    static Duration retryAfter(int status,List<String> values,Instant now){
        if(status!=429&&status<500)return Duration.ZERO;
        if(values.size()!=1)return NO_AUTOMATIC_RETRY;String value=values.getFirst().trim();
        try{if(value.matches("[0-9]{1,19}"))return Duration.ofSeconds(Long.parseLong(value));Instant date=ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();return date.isAfter(now)?Duration.between(now,date):Duration.ZERO;}catch(RuntimeException unknown){return NO_AUTOMATIC_RETRY;}
    }
    public void close(){closed=true;if(owned!=null)owned.close();}
    @Override public String toString(){return "ReviewedOfficialHttpSender[policy=bounded,credentials=redacted]";}
}