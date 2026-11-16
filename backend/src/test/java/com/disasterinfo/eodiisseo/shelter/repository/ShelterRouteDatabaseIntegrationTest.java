package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class ShelterRouteDatabaseIntegrationTest {
    static JdbcTemplate admin,jdbc;static String name;static DataSourceTransactionManager manager;
    @BeforeAll static void create(){
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE"))||!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(url))throw new IllegalStateException("Disposable audit cluster required");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));
        name="eodiisseo_verify_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+name);
        var source=new DriverManagerDataSource(url.replace("/postgres","/"+name),"eodiisseo_audit","");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
    }
    @AfterAll static void drop(){if(admin!=null&&name!=null&&name.matches("eodiisseo_verify_[0-9a-f]{32}"))admin.execute("drop database "+name+" with(force)");}
    UUID member(){UUID id=UUID.randomUUID();jdbc.update("insert into member values(?,'test','ACTIVE',now(),null)",id);return id;}
    @Test void interestAreaIsOwnedAndNeedsActualCenter(){
        UUID actor=member(),other=member(),area=UUID.randomUUID();String code=UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into admin_area(area_code,name,level,active) values(?,'test','SIGUNGU',true)",code);
        jdbc.update("insert into interest_area values(?,?,?,'test',true,now())",area,actor,code);
        var reader=new InterestAreaLocationRepository(jdbc);
        assertEquals(403,assertThrows(ApiProblem.class,()->reader.resolve(other,area)).status());
        assertEquals(404,assertThrows(ApiProblem.class,()->reader.resolve(actor,UUID.randomUUID())).status());
        assertEquals(503,assertThrows(ApiProblem.class,()->reader.resolve(actor,area)).status());
        jdbc.update("update admin_area set center_point=ST_SetSRID(ST_MakePoint(127.0,37.5),4326)::geography where area_code=?",code);
        assertEquals(37.5,reader.resolve(actor,area).latitude());assertEquals(127.0,reader.resolve(actor,area).longitude());
    }
    @Test void concurrentRouteBudgetIsSharedAcrossInstancesAndResetsNextMinute() throws Exception {
        UUID actor=member();var now=Instant.parse("2026-11-03T00:00:00Z");var clock=Clock.fixed(now,ZoneOffset.UTC);
        var a=new RouteRequestLimiter(jdbc,clock,manager);var b=new RouteRequestLimiter(jdbc,clock,manager);
        try(var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<20;i++){var limiter=i%2==0?a:b;jobs.add(pool.submit(()->{try{limiter.check(actor);return true;}catch(ApiProblem e){assertEquals(429,e.status());return false;}}));}
            int ok=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))ok++;
            assertEquals(10,ok);
        }
        new RouteRequestLimiter(jdbc,Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC),manager).check(actor);
        a.check(member());
        assertEquals(401,assertThrows(ApiProblem.class,()->a.check(null)).status());
    }
}
