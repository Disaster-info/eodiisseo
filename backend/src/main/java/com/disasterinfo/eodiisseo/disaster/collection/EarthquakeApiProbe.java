package com.disasterinfo.eodiisseo.disaster.collection;

import tools.jackson.databind.json.JsonMapper;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Standalone, metadata-only observation. No Spring, DB, coordinator, projection or raw storage. */
public final class EarthquakeApiProbe {
    public enum Route {
        PORTAL("https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg","serviceKey"),
        HUB("https://apihub.kma.go.kr/api/typ02/openApi/EqkInfoService/getEqkMsg","authKey");
        final String endpoint,keyParameter;
        Route(String endpoint,String keyParameter){this.endpoint=endpoint;this.keyParameter=keyParameter;}
    }
    public enum Format { JSON, XML }
    public record Plan(Route route,LocalDate from,LocalDate to,int page,int rows,Format format) {
        public Plan {
            Objects.requireNonNull(route);Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(format);
            if(!from.equals(to)||page<1||page>100||rows<1||rows>100)throw new IllegalArgumentException("Explicit bounded single-day plan required");
        }
    }
    /** Human-reviewed attestations, NOT a provider approval or a production admission artifact. */
    public record Authorization(Plan plan,Instant expiresAt,Instant keyValidUntil,int maxRequests,
            boolean developmentApiApproved,boolean allowedIpApproved,boolean remainingQuotaApproved,
            boolean metadataAccessApproved,String approvalReference) {
        public Authorization {Objects.requireNonNull(plan);Objects.requireNonNull(expiresAt);Objects.requireNonNull(keyValidUntil);Objects.requireNonNull(approvalReference);}
        public boolean permits(Plan request,Instant now){
            return plan.equals(request)&&maxRequests==1&&expiresAt.isAfter(now)&&keyValidUntil.isAfter(now)
                &&developmentApiApproved&&allowedIpApproved&&remainingQuotaApproved&&metadataAccessApproved
                &&approvalReference.matches("[A-Z0-9][A-Z0-9_.:-]{3,99}")
                // Initial observation only: never silently choose a date, scan, or query old archive dates.
                &&request.from().equals(LocalDate.ofInstant(now,ZoneId.of("Asia/Seoul")).minusDays(1));
        }
        @Override public String toString(){return "ProbeAuthorization[details=withheld]";}
    }
    public static final class Credentials {
        private final Map<Route,Supplier<String>> suppliers;
        public Credentials(Map<Route,Supplier<String>> suppliers){this.suppliers=Map.copyOf(suppliers);}
        String value(Route route){var supplier=suppliers.get(route);return supplier==null?null:supplier.get();}
        @Override public String toString(){return "ProbeCredentials[redacted]";}
    }
    @FunctionalInterface public interface Sender {OfficialHttp.HeadersReply get(URI uri,Duration timeout);}
    public record FieldShape(boolean present,String type,String format) {}
    public record ItemShape(int index,Map<String,FieldShape> fields) {public ItemShape{fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}}
    public record Observation(Instant requestedAt,Plan plan,Integer httpStatus,String contentType,String resultCode,String resultMsg,
            Integer pageNo,Integer numOfRows,Integer totalCount,int itemCount,List<ItemShape> items,String sha256,int responseBytes,
            boolean parsingSucceeded,boolean httpNormal,boolean providerNormal,boolean earthquakeRowsReceived,List<String> conditions) {
        public Observation {items=List.copyOf(items);conditions=List.copyOf(conditions);}
    }
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final List<String> FIELDS=List.of("stnId","tmFc","tmSeq","fcTp","cnt","tmEqk","tmMsc","lat","lon","loc","mt","inT","dep","cor","rem");
    private static final Set<String> MESSAGES=Set.of("NORMAL_SERVICE","NORMAL SERVICE","OK","NODATA_ERROR","UNKNOWN_ERROR","APPLICATION_ERROR","DB_ERROR","HTTP_ERROR","SERVICETIME_OUT","INVALID_REQUEST_PARAMETER_ERROR","NO_MANDATORY_REQUEST_PARAMETERS_ERROR","NO_OPENAPI_SERVICE_ERROR","SERVICE_ACCESS_DENIED_ERROR","TEMPORARILY_DISABLE_THE_SERVICEKEY_ERROR","LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR","SERVICE_KEY_IS_NOT_REGISTERED_ERROR","DEADLINE_HAS_EXPIRED_ERROR","UNREGISTERED_IP_ERROR","UNSIGNED_CALL_ERROR");
    private final Sender sender;private final Credentials credentials;private final Clock clock;
    private final Set<Route> consumed=ConcurrentHashMap.newKeySet();
    public EarthquakeApiProbe(Sender sender,Credentials credentials,Clock clock){this.sender=Objects.requireNonNull(sender);this.credentials=Objects.requireNonNull(credentials);this.clock=Objects.requireNonNull(clock);}
    public Observation observe(Plan plan,Authorization authorization){
        Instant now=clock.instant();
        if(authorization==null||!authorization.permits(plan,now))return absent(now,plan,"APPROVAL_REQUIRED");
        // Consumed before credential access/request, including failures. At most one attempt per route, two total.
        if(!consumed.add(plan.route()))return absent(now,plan,"REQUEST_BUDGET_EXHAUSTED");
        String key;
        try{key=credentials.value(plan.route());}catch(RuntimeException unavailable){return absent(now,plan,"KEY_UNAVAILABLE");}
        if(key==null||key.isBlank()||key.length()>1024)return absent(now,plan,"KEY_UNAVAILABLE");
        try{
            var response=sender.get(uri(plan,key),Duration.ofSeconds(15));
            return inspect(now,plan,response,key);
        }catch(RuntimeException uncertain){return absent(now,plan,"TRANSPORT_UNCERTAIN");}
    }
    private static URI uri(Plan p,String key){
        var q=new LinkedHashMap<String,String>();q.put(p.route().keyParameter,key);q.put("fromTmFc",p.from().format(DateTimeFormatter.BASIC_ISO_DATE));q.put("toTmFc",p.to().format(DateTimeFormatter.BASIC_ISO_DATE));q.put("pageNo",Integer.toString(p.page()));q.put("numOfRows",Integer.toString(p.rows()));q.put("dataType",p.format().name());
        return URI.create(p.route().endpoint+"?"+q.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")));
    }
    private static Observation absent(Instant now,Plan p,String reason){return new Observation(now,p,null,"UNOBSERVED",null,null,null,null,null,0,List.of(),null,0,false,false,false,false,List.of(reason));}
    private static Observation inspect(Instant now,Plan plan,OfficialHttp.HeadersReply response,String key){
        byte[] bytes=response.body();String sha=hash(bytes);var issues=new LinkedHashSet<String>();String type=contentType(response.contentTypes());
        boolean http=response.status()==200,parsed=false,normal=false;String code=null,msg=null;Integer page=null,rows=null,total=null;int count=0;var shapes=new ArrayList<ItemShape>();
        if(!http)issues.add("HTTP_ERROR");
        if(type.equals("UNRECOGNIZED")||type.equals("MISSING_OR_AMBIGUOUS"))issues.add("CONTENT_TYPE_UNVERIFIED");
        else if((plan.format()==Format.JSON&&!type.equals("application/json"))||(plan.format()==Format.XML&&!Set.of("application/xml","text/xml").contains(type)))issues.add("CONTENT_TYPE_MISMATCH");
        if(bytes.length==0)issues.add("EMPTY_BODY");else if(bytes.length>2_097_152)issues.add("BODY_LIMIT");else try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().strip();
            boolean xml=text.startsWith("<");if(xml!=(plan.format()==Format.XML))issues.add("FORMAT_MISMATCH");
            Map<String,Object> envelope=xml?xml(text):map(JSON.readValue(text,Map.class).get("response"));
            var header=map(envelope.get("header"));String rawCode=Objects.toString(header.get("resultCode"),"");String rawMsg=Objects.toString(header.get("resultMsg"),"");
            code=rawCode.matches("[0-9]{1,2}")&&!rawCode.contains(key)?rawCode:"REDACTED_UNRECOGNIZED";
            msg=MESSAGES.contains(rawMsg)&&!rawMsg.contains(key)?rawMsg:"REDACTED_UNRECOGNIZED";
            if(code.equals("REDACTED_UNRECOGNIZED"))issues.add("PROVIDER_CODE_UNRECOGNIZED");
            if(msg.equals("REDACTED_UNRECOGNIZED"))issues.add("PROVIDER_MESSAGE_UNRECOGNIZED");
            normal="00".equals(rawCode)||("0".equals(rawCode)&&"NORMAL_SERVICE".equals(rawMsg));parsed=true;
            if(!normal)issues.add(Set.of("03","3").contains(rawCode)&&"NODATA_ERROR".equals(rawMsg)?"NO_DATA_OBSERVATION":"PROVIDER_ERROR");
            if(envelope.get("body") instanceof Map<?,?>){
                var body=map(envelope.get("body"));page=integer(body.get("pageNo"));rows=integer(body.get("numOfRows"));total=integer(body.get("totalCount"));
                if(page==null||rows==null||total==null)issues.add("MISSING_OR_INVALID_PAGING_METADATA");
                if(page!=null&&page!=plan.page())issues.add("PAGE_MISMATCH");if(rows!=null&&rows!=plan.rows())issues.add("ROWS_MISMATCH");
                if(!body.containsKey("items"))issues.add("MISSING_ITEMS_CONTAINER");
                Object container=body.get("items");Object data=container instanceof Map<?,?>?((Map<?,?>)container).get("item"):null;
                if(container!=null&&!(container instanceof Map<?,?>)&&!container.equals(""))throw new IllegalArgumentException();
                List<?> list=data instanceof List<?> l?l:data instanceof Map<?,?>?List.of(data):List.of();
                if(data!=null&&!(data instanceof List<?>)&&!(data instanceof Map<?,?>)&&!data.equals(""))throw new IllegalArgumentException();
                count=list.size();if(count>100){issues.add("ITEM_OBSERVATION_LIMIT");}else for(int i=0;i<count;i++){
                    var item=map(list.get(i));var fields=new LinkedHashMap<String,FieldShape>();for(String name:FIELDS)fields.put(name,shape(item,name));shapes.add(new ItemShape(i+1,fields));
                    for(String name:List.of("stnId","tmFc","tmSeq","fcTp"))if(!item.containsKey(name)||item.get(name)==null||Objects.toString(item.get(name),"").isBlank())issues.add("INTERNAL_IDENTITY_INCOMPLETE");
                    for(String name:List.of("tmFc","tmEqk"))if(!Set.of("VALID_12_DIGITS","VALID_14_DIGITS").contains(fields.get(name).format()))issues.add("TIME_FORMAT_UNVERIFIED");
                    if(fields.get("tmFc").format().equals("VALID_14_DIGITS"))issues.add("ANNOUNCEMENT_TIME_PRECISION_UNVERIFIED");
                    if(fields.get("tmEqk").format().equals("VALID_12_DIGITS"))issues.add("OCCURRENCE_TIME_PRECISION_UNVERIFIED");
                    if(Set.of("VALID_12_DIGITS","VALID_14_DIGITS").contains(fields.get("tmFc").format())&&!Objects.toString(item.get("tmFc")).startsWith(plan.from().format(DateTimeFormatter.BASIC_ISO_DATE)))issues.add("ANNOUNCEMENT_DATE_MISMATCH");
                }
                if(total!=null&&(count>total||(total>0&&count==0)))issues.add("TOTAL_COUNT_CONTRADICTION");if(count>plan.rows())issues.add("REQUESTED_ROWS_EXCEEDED");
                if(count==0)issues.add("EMPTY_OBSERVATION_NOT_COVERAGE");
            }else if(normal)issues.add("MISSING_BODY");
        }catch(Exception invalid){parsed=false;normal=false;shapes.clear();issues.add("SCHEMA_INVALID");}
        issues.add("INCIDENT_AND_COVERAGE_UNVERIFIED");
        return new Observation(now,plan,response.status(),type,code,msg,page,rows,total,count,shapes,sha,bytes.length,parsed,http,normal,http&&normal&&parsed&&count>0,List.copyOf(issues));
    }
    private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException();return (Map<String,Object>)value;}
    private static Integer integer(Object value){try{String t=Objects.toString(value,"");if(!t.matches("[0-9]{1,5}"))return null;int n=Integer.parseInt(t);return n<=10000?n:null;}catch(RuntimeException invalid){return null;}}
    private static FieldShape shape(Map<String,Object> row,String name){
        if(!row.containsKey(name))return new FieldShape(false,"ABSENT","UNOBSERVED");Object v=row.get(name);String type=v==null?"NULL":v instanceof String?"STRING":v instanceof Number?"NUMBER":v instanceof Boolean?"BOOLEAN":v instanceof Map?"OBJECT":v instanceof List?"ARRAY":"OTHER";
        String format="NOT_EVALUATED";if(Set.of("tmFc","tmEqk").contains(name)){
            String t=Objects.toString(v,"");format="UNSUPPORTED";if(t.matches("[0-9]{12}([0-9]{2})?"))try{LocalDateTime.parse(t,DateTimeFormatter.ofPattern(t.length()==12?"uuuuMMddHHmm":"uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT));format=t.length()==12?"VALID_12_DIGITS":"VALID_14_DIGITS";}catch(DateTimeException e){format="INVALID_DATE";}
        }return new FieldShape(true,type,format);
    }
    private static String contentType(List<String> values){if(values.size()!=1)return "MISSING_OR_AMBIGUOUS";String value=values.getFirst().split(";",2)[0].strip().toLowerCase(Locale.ROOT);return Set.of("application/json","application/xml","text/xml","text/html").contains(value)?value:"UNRECOGNIZED";}
    private static Map<String,Object> xml(String text)throws Exception{
        var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());Element root=b.parse(new InputSource(new StringReader(text))).getDocumentElement();if(!root.getTagName().equals("response"))throw new IllegalArgumentException();return map(element(root,0));
    }
    private static Object element(Element e,int depth){if(depth>16)throw new IllegalArgumentException();var result=new LinkedHashMap<String,Object>();boolean children=false;for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child){children=true;Object v=element(child,depth+1);String key=child.getTagName();if(result.containsKey(key)){if(key.equals("item")){Object old=result.get(key);if(old instanceof List<?> l)((List<Object>)l).add(v);else{var list=new ArrayList<Object>();list.add(old);list.add(v);result.put(key,list);}}else throw new IllegalArgumentException();}else result.put(key,v);}return children?result:e.getTextContent();}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException("Digest unavailable");}}
    public static String metadataJson(Observation o){return JSON.writeValueAsString(o);}
}