package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.*;
import java.util.*;
/** Test-only independent Gateway process; fixed typed heartbeat, bounded stdin, no caller SQL/callback. */
public final class CollectionGatewayReplayProbeMain {
 public static void main(String[] args){char[] password=null;
  try{
   if(args.length!=2||!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/collection4c_[a-f0-9]{32}"))throw new IllegalStateException();
   String input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine();if(input==null||input.length()>8192)throw new IllegalStateException();
   var data=tools.jackson.databind.json.JsonMapper.builder().build().readValue(input,Map.class);password=((String)data.get("password")).toCharArray();UUID worker=UUID.fromString((String)data.get("worker"));
   var request=new CollectionWorkerCommandBoundary.Heartbeat(UUID.fromString((String)data.get("command")),worker,UUID.fromString((String)data.get("source")),UUID.fromString((String)data.get("run")),UUID.fromString((String)data.get("claim")),((Number)data.get("fence")).longValue(),((Number)data.get("generation")).longValue(),Instant.parse((String)data.get("until")),Base64.getDecoder().decode((String)data.get("signature")));
   try(var pool=new CollectionLoginPool(args[0],args[1],password,"restarted-gateway",1);var app=new CollectionProductionGatewayPostgresTest().context(pool)){
    var jdbc=new JdbcTemplate(pool);var manager=app.getBean(JpaTransactionManager.class);var gateway=new ProductionCollectionWriteGateway(jdbc,manager);
    var permit=new ProductionCollectionWriteGateway.Permit(worker,(String)data.get("capability"));
    var authorizer=new JdbcCollectionCommandAuthorizer(jdbc,()->new CollectionCommandPeerPort.Peer(worker,(String)data.get("peer"),request.until(),true),id->{if(!worker.equals(id))throw new IllegalStateException();return permit;},CollectionOperationMode.DISPOSABLE_AUDIT);
    var boundary=new CollectionWorkerCommandBoundary(jdbc,manager,gateway,authorizer,new JdbcCollectionCommandJournal(jdbc,manager,worker),Duration.ofMinutes(1),Clock.systemUTC());
    System.out.println(boundary.heartbeat(request)?"REPLAY_APPLIED":"REPLAY_DUPLICATE");
   }
  }catch(Throwable rejected){System.out.println("REPLAY_REJECTED");System.exit(2);}finally{if(password!=null)Arrays.fill(password,'\0');}
 }
}
