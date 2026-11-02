package com.disasterinfo.eodiisseo.collection.client;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.collection.domain.CollectedRecord;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

/** Only documented, paginated JSON contracts. No provider URLs or bodies enter failure logs. */
@Component @Profile("!test")
public class PublicCollectionClient implements CollectionClient {
    private record Endpoint(String url,String key,boolean safety) {}
    private final Map<String,Endpoint> endpoints;private final RestClient http;private final ObjectMapper json;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PublicCollectionClient(Environment env,ObjectMapper json,Clock clock){this(env,json,clock,defaultHttp());}
    PublicCollectionClient(Environment env,ObjectMapper json,Clock clock,RestClient http){
        this.json=json;this.clock=clock;
        String kma=first(env,"KMA_PUBLIC_SERVICE_KEY","KMA_SERVICE_KEY","DATA_GO_KR_SERVICE_KEY");
        endpoints=Map.of(
            "KMA_EARTHQUAKE",new Endpoint("https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg",kma,false),
            "KMA_WARNING",new Endpoint("https://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrWrnList",kma,false),
            "SAFETYDATA_DISASTER_MESSAGE",new Endpoint("https://www.safetydata.go.kr/V2/api/DSSP-IF-00247",first(env,"SAFETYDATA_DISASTER_MESSAGE_KEY","SAFETY_DISASTER_MESSAGE_KEY"),true),
            "SAFETYDATA_EARTHQUAKE_SHELTER",new Endpoint("https://www.safetydata.go.kr/V2/api/DSSP-IF-10943",first(env,"SAFETYDATA_EARTHQUAKE_SHELTER_KEY","SAFETY_SERVICE_KEY"),true));
        this.http=http;
    }
    private static RestClient defaultHttp(){var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(10));return RestClient.builder().requestFactory(factory).build();}
    private static String first(Environment env,String... keys){for(var k:keys){String v=env.getProperty(k,"");if(!v.isBlank())return v;}return "";}
    public Set<String> configuredSources(){var result=new HashSet<String>();endpoints.forEach((k,v)->{if(!v.key().isBlank())result.add(k);});return Set.copyOf(result);}
    public List<CollectedRecord> fetch(String source,Runnable heartbeat){
        var endpoint=endpoints.get(source);
        if(endpoint==null||endpoint.key().isBlank())throw new ApiProblem(503,"COLLECTION_SOURCE_NOT_CONFIGURED","제공처 설정이 필요합니다.");
        var records=new LinkedHashMap<String,CollectedRecord>();int received=0;Integer expected=null;
        String today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).format(DateTimeFormatter.BASIC_ISO_DATE);
        String start=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        for(int page=1;page<=1000;page++){
            heartbeat.run();final int currentPage=page;String raw;
            try{
                raw=http.get().uri(endpoint.url() + (endpoint.safety()?"?serviceKey={key}&returnType=json":"?serviceKey={key}&dataType=JSON")
                    +"&pageNo="+currentPage+"&numOfRows=100"
                    +(source.equals("SAFETYDATA_DISASTER_MESSAGE")?"&crtDt="+start:!endpoint.safety()?"&fromTmFc="+start+"&toTmFc="+today:""),endpoint.key()).retrieve().body(String.class);
            }catch(RuntimeException e){throw new ApiProblem(502,"COLLECTION_PROVIDER_FAILED","외부 API 조회에 실패했습니다.");}
            JsonNode root;try{root=json.readTree(raw);}catch(RuntimeException e){throw invalid();}
            var envelope=endpoint.safety()?root:root.path("response");
            String code=envelope.path("header").path("resultCode").asText();
            // KMA explicitly represents no matching data with resultCode 03.
            if(!endpoint.safety()&&code.equals("03")){if(page==1)return List.of();throw invalid();}
            if(!code.equals("00"))throw invalid();
            var body=endpoint.safety()?envelope:envelope.path("body");
            JsonNode total=body.path("totalCount");
            if(!total.isIntegralNumber()&&!total.isTextual())throw invalid();
            int count;try{count=Integer.parseInt(total.asText());}catch(Exception e){throw invalid();}
            if(count<0||(expected!=null&&expected!=count))throw invalid();expected=count;
            var items=endpoint.safety()?envelope.path("body"):body.path("items").path("item");
            if(count==0){if(page!=1)throw invalid();return List.of();}
            if(!items.isArray()||items.isEmpty())throw invalid();
            for(var item:items){
                if(!item.isObject())throw invalid();
                @SuppressWarnings("unchecked") Map<String,Object> payload=json.convertValue(item,Map.class);
                String key=recordKey(source,payload);var record=new CollectedRecord(key,payload);
                var old=records.putIfAbsent(key,record);if(old!=null)throw invalid();
            }
            received+=items.size();if(received>=count){if(received!=count)throw invalid();return List.copyOf(records.values());}
        }
        throw new ApiProblem(502,"COLLECTION_INCOMPLETE","전체 페이지를 받지 못했습니다.");
    }
    private String recordKey(String source,Map<String,Object> row){
        if(source.equals("SAFETYDATA_DISASTER_MESSAGE"))return required(row,"SN");
        if(source.equals("SAFETYDATA_EARTHQUAKE_SHELTER"))return required(row,"ARCD")+":"+required(row,"ACMDFCLTY_SN");
        // tmSeq resets monthly; retain year-month, issuing station and announcement identity.
        String time=required(row,"tmFc");if(time.length()<6)throw invalid();
        String key=time.substring(0,6)+":"+required(row,"stnId")+":"+required(row,"tmSeq");
        if(source.equals("KMA_EARTHQUAKE"))key+=":"+required(row,"fcTp");
        return key;
    }
    private String required(Map<String,Object> row,String key){var v=row.get(key);if(v==null||v.toString().isBlank())throw invalid();return v.toString();}
    private ApiProblem invalid(){return new ApiProblem(502,"COLLECTION_INVALID_RESPONSE","제공처 응답 규격을 확인하세요.");}
}
