package com.disasterinfo.eodiisseo.collection.operations;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
/** Test child: fixed diagnostic operations only. No SQL/RPC/callback supplied by its caller. */
public final class CollectionRoleProbeMain {
 public static void main(String[] args){
  char[] password=null;
  try{
   if(args.length!=5||System.getenv().keySet().stream().anyMatch(k->k.matches("(?i).*(GATEWAY|OPS|PASSWORD|TOKEN|CREDENTIAL).*")))throw new IllegalStateException();
   password=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine().toCharArray();
   var tls=new CollectionTlsPolicy(Path.of(args[2]),args[3],Instant.now().plusSeconds(60));
   try(var pool=new CollectionLoginPool(args[0],args[1],password,"child-role",1,tls,Instant.now().plusSeconds(60),1,()->1);var c=pool.getConnection();var s=c.createStatement()){
    try(var row=s.executeQuery("select session_user,pg_backend_pid(),ssl from pg_stat_ssl where pid=pg_backend_pid()")){
     if(!row.next()||!args[1].equals(row.getString(1))||!row.getBoolean(3))throw new IllegalStateException();
    }
    try{s.execute("insert into isolation_probe values(1)");throw new IllegalStateException("DML accepted");}catch(java.sql.SQLException expected){if(!"42501".equals(expected.getSQLState()))throw expected;}
    System.out.println("ISOLATED_LOGIN_OK:"+ProcessHandle.current().pid()+":"+System.getProperty("user.name"));
   }
  }catch(Throwable failure){System.out.println("REJECTED");System.exit(2);}finally{if(password!=null)java.util.Arrays.fill(password,'\0');}
 }
}
