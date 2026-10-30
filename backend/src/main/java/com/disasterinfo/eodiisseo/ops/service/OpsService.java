package com.disasterinfo.eodiisseo.ops.service;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.ops.port.ShelterRefreshGateway;

import com.disasterinfo.eodiisseo.ops.repository.OpsRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.*;

@Service @Profile("!test")
public class OpsService {
    private final OpsRepository repository;private final Environment environment;private final ObjectProvider<ShelterRefreshGateway> refresh;
    public OpsService(OpsRepository repository,Environment environment,ObjectProvider<ShelterRefreshGateway> refresh) {
        this.repository=repository;this.environment=environment;this.refresh=refresh;
        if(production() && mockEnabled()) throw new IllegalStateException("Mock API must be disabled in production");
    }
    public Map<String,Object> status(String mode) {
        mode=mode(mode);requireSchema("data_source","collection_run");
        var rows=repository.listApiStatuses(mode,mode);
        rows.forEach(r->r.computeIfPresent("lastSuccessfulCollectedAt",(k,v)->instant(v)));
        return object("mockApiEnabled",mockEnabled(),"apiStatuses",rows);
    }
    @Transactional(readOnly=true) public Map<String,Object> disasters(String type,String status,Instant from,Instant to,int page,int size,String mode) {
        mode=mode(mode);validatePage(page,size,from,to);requireSchema("disaster","disaster_type","source_disaster","data_source","disaster_area","admin_area");
        if(type!=null && !Set.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE").contains(type)) throw ApiProblem.bad("재난 유형을 확인하세요.");
        if(status!=null && !Set.of("OCCURRED","ONGOING","ENDED","LIFTED").contains(status)) throw ApiProblem.bad("재난 상태를 확인하세요.");
        var result=repository.findDisasterPage(type,status,from,to,page,size,mode);
        var rows=result.rows();var total=result.total();
        rows.forEach(r->{for(var k:List.of("occurredAt","updatedAt","collectedAt")) if(r.get(k)!=null) r.put(k,instant(r.get(k)));});
        return page("disasters",rows,page,size,total);
    }
    @Transactional(readOnly=true) public Map<String,Object> errors(String source,Instant from,Instant to,int page,int size,String mode) {
        mode=mode(mode);validatePage(page,size,from,to);requireSchema("service_error_log","collection_run","data_source");
        if(source!=null && (source.isBlank()||source.length()>100)) throw ApiProblem.bad("오류 대상을 확인하세요.");
        var result=repository.findErrorPage(source,from,to,page,size,mode);
        var rows=result.rows();var total=result.total();
        // The foreign collector's safe_message has not yet been audited. Never relay arbitrary provider bodies or URLs.
        for(var r:rows) {r.put("occurredAt",instant(r.get("occurredAt")));r.put("source",safeIdentifier(r.get("source"),"UNKNOWN_SOURCE"));r.put("errorType",safeIdentifier(r.get("errorType"),"UNKNOWN_ERROR"));r.put("message",safeMessage((String)r.get("errorType")));}
        return page("errorLogs",rows,page,size,total);
    }
    public com.disasterinfo.eodiisseo.ops.dto.OpsDto.Accepted refresh(UUID member,String source,String mode) {
        mode=mode(mode);requireSchema("data_source");
        if(source!=null && repository.countActiveShelterSource(source)!=1) throw ApiProblem.bad("지원하지 않는 대피소 제공처입니다.");
        var gateway=refresh.getIfAvailable();
        if(gateway==null) throw new ApiProblem(503,"COLLECTION_WORKER_NOT_CONNECTED","수집 담당의 작업 접수 및 worker 연결이 필요합니다.");
        return gateway.request(member,source,mode);
    }
    private String mode(String mode) { if(mode==null) return "LIVE";if(!Set.of("LIVE","MOCK").contains(mode) || production()&&mode.equals("MOCK")) throw ApiProblem.bad("허용되지 않는 데이터 모드입니다.");return mode; }
    private boolean production() { return Arrays.stream(environment.getActiveProfiles()).anyMatch(p->p.equals("production")||p.equals("prod")); }
    private boolean mockEnabled() { return environment.getProperty("MOCK_API_ENABLED",Boolean.class,false)||environment.getProperty("USE_MOCK_DISASTER_API",Boolean.class,false); }
    public static void validatePage(int page,int size,Instant from,Instant to) { if(page<0 || size<1 || size>100 || from!=null&&to!=null&&!from.isBefore(to)) throw ApiProblem.bad("페이지 또는 조회 기간을 확인하세요."); }
    private Map<String,Object> page(String key,List<Map<String,Object>> rows,int page,int size,long total) { return object(key,rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size); }
    private void requireSchema(String... names) { for(var name:names) if(!repository.tableExists(name)) throw new ApiProblem(503,"OPERATIONS_DEPENDENCY_NOT_READY","운영 조회용 공통 스키마 연결이 필요합니다."); }
    public static String safeMessage(String code) { return switch(code==null?"":code) {case "CONNECTION_TIMEOUT","TIMEOUT"->"외부 API 응답 시간이 초과되었습니다.";case "RATE_LIMITED","QUOTA_EXCEEDED"->"외부 API 호출 한도를 확인하세요.";default->"처리 오류가 발생했습니다. 서버의 비밀정보를 제외한 운영 기록을 확인하세요.";}; }
    public static String safeIdentifier(Object value,String fallback) { return value instanceof String s && s.matches("[A-Z][A-Z0-9_]{0,99}")?s:fallback; }
}
