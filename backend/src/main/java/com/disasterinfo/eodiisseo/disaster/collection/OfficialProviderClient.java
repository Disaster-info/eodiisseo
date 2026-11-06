package com.disasterinfo.eodiisseo.disaster.collection;

import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.xml.sax.InputSource;
import java.io.StringReader;
import tools.jackson.databind.json.JsonMapper;

/** Separate fixed endpoint contracts. Keys are DECODED secret values, encoded exactly once. */
public final class OfficialProviderClient {
    public enum Provider {
        EARTHQUAKE("KMA_EARTHQUAKE","https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg"),
        HEAVY_RAIN("KMA_HEAVY_RAIN","https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnCd"),
        WILDFIRE_MESSAGES("MOIS_DISASTER_MESSAGES","https://www.safetydata.go.kr/V2/api/DSSP-IF-00247");
        public final String endpointKey; public final URI endpoint;
        Provider(String key,String url){endpointKey=key;endpoint=URI.create(url);}
    }
    @FunctionalInterface public interface Transport { OfficialHttp.Reply get(URI url,Duration timeout); }
    public record Page(int number,int total,List<Map<String,Object>> items,byte[] raw) {
        public Page {items=List.copyOf(items);raw=raw.clone();}
        @Override public byte[] raw(){return raw.clone();}
        @Override public String toString(){return "OfficialPage[number="+number+", total="+total+", raw=redacted]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Provider provider; private final Transport transport; private final URI endpoint; private final String keyParameter;
    public OfficialProviderClient(Provider provider,Transport transport){this(provider,transport,Objects.requireNonNull(provider).endpoint,"serviceKey");}
    private OfficialProviderClient(Provider provider,Transport transport,URI endpoint,String keyParameter){
        this.provider=Objects.requireNonNull(provider);this.transport=Objects.requireNonNull(transport);this.endpoint=endpoint;this.keyParameter=keyParameter;
    }
    /** Explicit approved Hub contract; never an automatic credential/endpoint fallback in production. */
    public static OfficialProviderClient earthquakeHub(Transport transport){
        return new OfficialProviderClient(Provider.EARTHQUAKE,transport,
            URI.create("https://apihub.kma.go.kr/api/typ02/openApi/EqkInfoService/getEqkMsg"),"authKey");
    }
    public Provider provider(){return provider;}
    private long nextRequestNanos;
    public Page page(String decodedKey,LocalDate from,LocalDate to,int page) {
        return page(decodedKey,from,to,page,100);
    }
    /** Explicit small development probes reuse the same transport, schema validation and rate limit. */
    public synchronized Page page(String decodedKey,LocalDate from,LocalDate to,int page,int rows) {
        if(rows<1||rows>100)throw new CollectionFailure("ROW_LIMIT");
        if(decodedKey==null||decodedKey.isBlank()||decodedKey.length()>1024)throw new CollectionFailure("KEY_REQUIRED");
        if(from==null||to==null||from.getYear()<1||to.getYear()>9999||to.isBefore(from)||to.isAfter(from.plusDays(2))||page<1||page>100)
            throw new CollectionFailure("WINDOW_LIMIT");
        var parameters=new LinkedHashMap<String,String>();
        parameters.put(keyParameter,decodedKey);parameters.put("pageNo",""+page);parameters.put("numOfRows",Integer.toString(rows));
        if(provider==Provider.WILDFIRE_MESSAGES){
            parameters.put("returnType","json");
            // MOIS public apiDataTable?dataSn=228, checked 2026-11-03: crtDt STRING, YYYYMMDD start date.
            // Inclusivity, upper bound, stable ordering and modification replay are NOT documented guarantees.
            if(!from.equals(to))throw new CollectionFailure("UNSUPPORTED_END_DATE");
            parameters.put("crtDt",from.format(DateTimeFormatter.BASIC_ISO_DATE));
        }else{
            parameters.put("dataType","JSON");parameters.put("fromTmFc",from.format(DateTimeFormatter.BASIC_ISO_DATE));
            parameters.put("toTmFc",to.format(DateTimeFormatter.BASIC_ISO_DATE));
            if(provider==Provider.HEAVY_RAIN){parameters.put("warningType","2");parameters.put("stnId","108");}
        }
        String query=parameters.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        long wait=nextRequestNanos-System.nanoTime();
        if(wait>0)try{java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CollectionFailure("INTERRUPTED");}
        nextRequestNanos=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        OfficialHttp.Reply reply=transport.get(URI.create(endpoint+"?"+query),Duration.ofSeconds(15));
        if(reply.status()!=200)throw new CollectionFailure(reply.status()==429?"RATE_LIMIT":reply.status()>=500?"PROVIDER_UNAVAILABLE":"HTTP_REJECTED");
        var decoded=decode(reply.body(),page);
        if(decoded.items().size()>rows)throw new CollectionFailure("ROW_LIMIT");
        return decoded;
    }
    public Page decode(byte[] bytes,int expectedPage) {
        if(bytes==null||bytes.length==0||bytes.length>2_097_152)throw new CollectionFailure("EMPTY_OR_OVERSIZED");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().strip();
            Map<String,Object> root=text.startsWith("<")?xml(text):JSON.readValue(text,Map.class);
            Map<String,Object> envelope=map(root.getOrDefault("response",root));
            Map<String,Object> header=map(envelope.get("header"));
            String code=Objects.toString(header.get("resultCode"),"");
            // Portal table: 00; official KMA guide example: 0 + NORMAL_SERVICE. Do not relax MOIS errors.
            boolean normal="00".equals(code)||(provider!=Provider.WILDFIRE_MESSAGES && "0".equals(code)
                && "NORMAL_SERVICE".equals(header.get("resultMsg")));
            // Official KMA guides define 03/3 + NODATA_ERROR. It is an observation, not coverage proof.
            if(provider!=Provider.WILDFIRE_MESSAGES && Set.of("03","3").contains(code)
                && "NODATA_ERROR".equals(header.get("resultMsg")))throw new CollectionFailure("NO_DATA");
            if(!normal)throw new CollectionFailure(Set.of("20","30","31","32","33","34").contains(code)?"AUTH_REJECTED":
                Set.of("22","23").contains(code)?"RATE_LIMIT":"PROVIDER_CODE");
            Map<String,Object> body;Object rows;Object total;
            if(provider==Provider.WILDFIRE_MESSAGES){
                rows=envelope.get("body");total=envelope.get("totalCount");body=envelope;
            }else{
                body=map(envelope.get("body"));total=body.get("totalCount");Object container=body.get("items");
                rows=container instanceof Map<?,?>?((Map<?,?>)container).get("item"):null;
            }
            int count=number(total);if(count<0||count>10000)throw new CollectionFailure("PAGE_LIMIT");
            if(body.containsKey("pageNo")&&number(body.get("pageNo"))!=expectedPage)throw new CollectionFailure("PAGE_MISMATCH");
            List<Map<String,Object>> items=new ArrayList<>();
            if(rows instanceof List<?> list){for(Object row:list)items.add(Collections.unmodifiableMap(new TreeMap<>(map(row))));}
            else if(rows instanceof Map<?,?>)items.add(Collections.unmodifiableMap(new TreeMap<>(map(rows))));
            else if(rows!=null&&!rows.equals(""))throw new CollectionFailure("SCHEMA");
            if(items.size()>100 || (count>0&&items.isEmpty()))throw new CollectionFailure("PAGE_INCOMPLETE");
            return new Page(expectedPage,count,items,bytes);
        }catch(CollectionFailure e){throw e;}catch(Exception e){throw new CollectionFailure("SCHEMA");}
    }
    private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw new CollectionFailure("SCHEMA");return (Map<String,Object>)value;}
    private static int number(Object value){try{return Integer.parseInt(Objects.toString(value,""));}catch(Exception e){throw new CollectionFailure("SCHEMA");}}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    /** JSON/XML normalize to the same canonical field map; exact wire bytes are separately archived. */
    private static Map<String,Object> xml(String text)throws Exception{
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        Element root=builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
        if(!"response".equals(root.getTagName())) {
            var codes=root.getElementsByTagName("returnReasonCode");
            if(codes.getLength()==1) {
                String code=codes.item(0).getTextContent().strip();
                throw new CollectionFailure(Set.of("20","30","31","32","33","34").contains(code)?"AUTH_REJECTED":
                    Set.of("22","23").contains(code)?"RATE_LIMIT":"PROVIDER_CODE");
            }
            throw new CollectionFailure("PROVIDER_CODE");
        }
        return Map.of("response",element(root));
    }
    private static Object element(Element parent){
        Map<String,Object> map=new LinkedHashMap<>();List<Element> children=new ArrayList<>();
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e)children.add(e);
        if(children.isEmpty())return parent.getTextContent();
        for(Element child:children){
            Object value=element(child);String key=child.getTagName();
            if(map.containsKey(key)){
                Object old=map.get(key);
                if(old instanceof List<?> list)((List<Object>)list).add(value);
                else {var values=new ArrayList<Object>();values.add(old);values.add(value);map.put(key,values);}
            }else map.put(key,value);
        }
        return map;
    }
    public static String canonical(Map<String,Object> item){return JSON.writeValueAsString(new TreeMap<>(item));}
}
