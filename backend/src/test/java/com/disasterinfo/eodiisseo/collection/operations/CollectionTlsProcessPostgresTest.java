package com.disasterinfo.eodiisseo.collection.operations;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="EODIISSEO_TLS_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class CollectionTlsProcessPostgresTest {
 Path root;String caHash;String url="jdbc:postgresql://127.0.0.1:55435/postgres";CollectionLoginPool admin;List<String> roles=new ArrayList<>();Map<String,char[]> passwords=new HashMap<>();
 @BeforeAll void open()throws Exception{
  root=Path.of(Objects.requireNonNull(System.getenv("EODIISSEO_TLS_TEST_ROOT"))).toRealPath();
  if(!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())||!root.getFileName().toString().startsWith("c4c3-tls-"))throw new IllegalStateException("Isolated TLS TEMP root only");
  caHash=hash(root.resolve("ca.crt"));char[] pw=Files.readString(root.resolve("bootstrap.password")).toCharArray();
  try{admin=new CollectionLoginPool(url,"c4c3_bootstrap",pw,"tls-bootstrap",3,tls("ca.crt"),Instant.now().plusSeconds(3600),1,()->1);}finally{Arrays.fill(pw,'\0');}
  try(var c=admin.getConnection();var s=c.createStatement()){
   s.execute("create table isolation_probe(id int primary key)");s.execute("revoke all on isolation_probe from public");
   for(int i=0;i<4;i++){
    String role="c4c3_"+UUID.randomUUID().toString().replace("-","");var bytes=new byte[32];new SecureRandom().nextBytes(bytes);char[] secret=HexFormat.of().formatHex(bytes).toCharArray();Arrays.fill(bytes,(byte)0);roles.add(role);passwords.put(role,secret);
    try{s.execute("create role "+role+" login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '"+new String(secret)+"'");}catch(SQLException e){throw new IllegalStateException("Temporary role creation failed");}
   }
  }
 }
 static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
 CollectionTlsPolicy tls(String file)throws Exception{return new CollectionTlsPolicy(root.resolve(file),hash(root.resolve(file)),Instant.now().plusSeconds(3600));}
 CollectionLoginPool pool(String endpoint,String role,CollectionTlsPolicy policy)throws Exception{return new CollectionLoginPool(endpoint,role,passwords.get(role),"tls-probe",1,policy,Instant.now().plusSeconds(60),1,()->1);}
 @AfterAll void close()throws Exception{
  if(admin!=null){try(var c=admin.getConnection();var s=c.createStatement()){s.execute("drop table if exists isolation_probe");for(String r:roles)s.execute("drop role "+r);assertEquals(0,countRoles(s));}finally{admin.close();}}
  passwords.values().forEach(p->Arrays.fill(p,'\0'));passwords.clear();
 }
 int countRoles(Statement s)throws Exception{try(var row=s.executeQuery("select count(*) from pg_roles where rolname like 'c4c3_%' and rolname<>'c4c3_bootstrap'")){row.next();return row.getInt(1);}}
 @Test void actualTlsLoginAndRoleDenial()throws Exception{
  for(String r:roles)try(var p=pool(url,r,tls("ca.crt"));var c=p.getConnection();var s=c.createStatement()){
   try(var row=s.executeQuery("select ssl from pg_stat_ssl where pid=pg_backend_pid()")){assertTrue(row.next());assertTrue(row.getBoolean(1));}
   for(String other:roles)if(!r.equals(other)){var denied=assertThrows(SQLException.class,()->s.execute("set role "+other));assertEquals("42501",denied.getSQLState());}
   assertThrows(SQLException.class,()->s.execute("insert into isolation_probe values(1)"));
  }
 }
 @Test void untrustedCaAndWrongServerIdentityAreRejected()throws Exception{
  var plain=new Properties();plain.setProperty("user",roles.getFirst());plain.setProperty("password",new String(passwords.get(roles.getFirst())));plain.setProperty("sslmode","disable");
  try{assertThrows(SQLException.class,()->DriverManager.getConnection(url,plain));}finally{plain.clear();}
  try(var wrong=pool(url,roles.getFirst(),tls("wrong-ca.crt"))){assertThrows(SQLException.class,wrong::getConnection);}
  try(var wrong=pool("jdbc:postgresql://localhost:55435/postgres",roles.getFirst(),tls("ca.crt"))){assertThrows(SQLException.class,wrong::getConnection);}
 }
 @Test void expiredTrustMissingCredentialAndRotatedLeaseAreRejected()throws Exception{
  assertThrows(IllegalArgumentException.class,()->new CollectionLoginPool(url,roles.getFirst(),null,"missing",1));
  assertThrows(IllegalStateException.class,()->new CollectionLoginPool(url,roles.getFirst(),passwords.get(roles.getFirst()),"expired",1,tls("ca.crt"),Instant.now().minusSeconds(1),1,()->1));
  var generation=new java.util.concurrent.atomic.AtomicLong(1);String role=roles.getFirst();char[] old=passwords.get(role).clone();
  try(var p=new CollectionLoginPool(url,role,old,"rotation",1,tls("ca.crt"),Instant.now().plusSeconds(60),1,generation::get)){
   try(var c=p.getConnection()){assertTrue(c.isValid(2));}
   var bytes=new byte[32];new SecureRandom().nextBytes(bytes);char[] replacement=HexFormat.of().formatHex(bytes).toCharArray();Arrays.fill(bytes,(byte)0);
   try(var c=admin.getConnection();var statement=c.createStatement()){
    try{statement.execute("alter role "+role+" password '"+new String(replacement)+"'");}catch(SQLException failure){throw new IllegalStateException("Test credential rotation failed");}
   }
   Arrays.fill(passwords.put(role,replacement),'\0');generation.incrementAndGet();assertThrows(SQLException.class,p::getConnection);
   try(var stale=new CollectionLoginPool(url,role,old,"old-credential",1,tls("ca.crt"),Instant.now().plusSeconds(60),2,()->2)){assertThrows(SQLException.class,stale::getConnection);}
   try(var current=new CollectionLoginPool(url,role,replacement,"new-credential",1,tls("ca.crt"),Instant.now().plusSeconds(60),2,()->2);var c=current.getConnection()){assertTrue(c.isValid(2));}
  }finally{Arrays.fill(old,'\0');}
  String expiredRole=roles.getLast();
  try{
   try(var c=admin.getConnection();var statement=c.createStatement()){statement.execute("alter role "+expiredRole+" valid until '2000-01-01T00:00:00Z'");}
   try(var expiredLogin=pool(url,expiredRole,tls("ca.crt"))){assertThrows(SQLException.class,expiredLogin::getConnection);}
  }finally{try(var c=admin.getConnection();var statement=c.createStatement()){statement.execute("alter role "+expiredRole+" valid until 'infinity'");}}
  var expired=new CollectionTlsPolicy(root.resolve("ca.crt"),caHash,Instant.now().minusSeconds(1));assertThrows(IllegalStateException.class,expired::verify);
 }
 @Test void separateJvmProcessesReceiveOnlyTheirOwnCredentialButSameOsPrincipalRemainsABlocker()throws Exception{
  var pids=new HashSet<Long>();var snapshots=new HashSet<CollectionProcessBoundary.Process>();var kinds=CollectionLoginPlane.Kind.values();var mapping=new EnumMap<CollectionLoginPlane.Kind,String>(CollectionLoginPlane.Kind.class);
  for(int i=0;i<3;i++){
   String role=roles.get(i);mapping.put(kinds[i],role);
   var cp=new LinkedHashSet<String>();for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof java.net.URLClassLoader u)for(var uri:u.getURLs())if(uri.getProtocol().equals("file"))cp.add(Path.of(uri.toURI()).toString());
   cp.add(System.getProperty("java.class.path"));
   var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dlogback.configurationFile="+Objects.requireNonNull(getClass().getResource("/collection4c3/child-logback.xml")).toExternalForm(),"-cp",String.join(java.io.File.pathSeparator,cp),CollectionRoleProbeMain.class.getName(),url,role,root.resolve("ca.crt").toString(),caHash,kinds[i].name());
   var systemRoot=System.getenv("SystemRoot");builder.environment().clear();if(systemRoot!=null)builder.environment().put("SystemRoot",systemRoot);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
   Process child=builder.start();
   var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{String marker="";try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.startsWith("ISOLATED_LOGIN_OK:")&&line.length()<256)marker=line;}catch(java.io.IOException failure){return "";}return marker;});
   try{
    try(var out=child.outputWriter()){out.write(passwords.get(role));out.newLine();}
    assertTrue(child.waitFor(30,TimeUnit.SECONDS));String result=output.get(3,TimeUnit.SECONDS);assertEquals(0,child.exitValue(),"Child rejected isolated binding");assertTrue(result.startsWith("ISOLATED_LOGIN_OK:"));
    var fields=result.split(":",3);long pid=Long.parseLong(fields[1]);assertNotEquals(ProcessHandle.current().pid(),pid);assertTrue(pids.add(pid));
    snapshots.add(new CollectionProcessBoundary.Process(pid,kinds[i],fields[2],"a".repeat(64),Set.of(role)));
   }finally{if(child.isAlive())child.destroyForcibly();}
  }
  assertEquals(3,pids.size());assertTrue(CollectionProcessBoundary.blockers(new CollectionProcessBoundary.Snapshot(snapshots,Instant.now().minusSeconds(1),Instant.now().plusSeconds(30),true,true),mapping,Instant.now(),true).contains("FRESH_AUTHORITATIVE_PROCESS_INVENTORY_REQUIRED"));
  assertTrue(CollectionProcessBoundary.blockers(new CollectionProcessBoundary.Snapshot(snapshots,Instant.now().minusSeconds(1),Instant.now().plusSeconds(30),true,false),mapping,Instant.now(),true).contains("PROCESS_OR_CREDENTIAL_PLANE_MIXED"));
 }
}
