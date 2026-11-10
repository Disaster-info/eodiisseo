package com.disasterinfo.eodiisseo.disaster.collection;

import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.disasterinfo.eodiisseo.disaster.collection.EarthquakeApiProbe.*;

/** Explicit standalone entry point. Default CHECK neither loads credentials nor opens an HTTP client. */
public final class EarthquakeProbeCli {
    interface Session extends Sender,AutoCloseable { @Override void close(); }
    @FunctionalInterface interface SenderFactory {Session open();}
    private static final Set<String> FIELDS=Set.of("route","from","to","page","rows","format","expires-at","key-valid-until","max-requests","development-api-approved","allowed-ip-approved","remaining-quota-approved","metadata-access-approved","approval-reference","body-storage");
    private EarthquakeProbeCli(){}
    public static void main(String[] args){System.exit(run(args,System::getenv,EarthquakeProbeCli::live,System.out,Clock.systemUTC()));}
    private static Session live(){
        var sender=ReviewedOfficialHttpSender.production();
        return new Session(){public OfficialHttp.HeadersReply get(URI uri,Duration timeout){return sender.observe(uri,timeout);}public void close(){sender.close();}};
    }
    static int run(String[] args,Function<String,String> environment,SenderFactory factory,PrintStream output,Clock clock){
        try{
            var options=new LinkedHashMap<String,String>();boolean execute=false;boolean modeSpecified=false;
            for(String arg:args){
                if(arg.equals("--check")||arg.equals("--execute-approved")){if(modeSpecified)throw new IllegalArgumentException();modeSpecified=true;execute=arg.equals("--execute-approved");continue;}
                int split=arg.indexOf('=');if(!arg.startsWith("--")||split<3||options.putIfAbsent(arg.substring(2,split),arg.substring(split+1))!=null)throw new IllegalArgumentException();
            }
            if(!options.keySet().equals(Set.of("route","from","to","page","rows","format","approval-file")))throw new IllegalArgumentException();
            var plan=plan(options);var path=Path.of(options.get("approval-file")).toAbsolutePath().normalize();var authorization=authorization(path);
            if(!authorization.permits(plan,clock.instant())){output.println("APPROVAL_REQUIRED_NO_REQUEST");return 2;}
            if(!execute){output.println("PREFLIGHT_OK_NETWORK_NOT_USED_CREDENTIALS_NOT_READ");return 0;}
            // One execution per approval file; consumed even on unknown/failed HTTP or startup failure.
            // Never deleted/reused automatically. CHECK never creates this marker.
            Path marker=path.resolveSibling(path.getFileName()+".consumed");
            try{Files.writeString(marker,"METADATA_ONLY_CONSUMED "+clock.instant()+" "+plan.route()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
            catch(Exception unavailable){output.println("APPROVAL_ALREADY_CONSUMED_OR_UNAVAILABLE_NO_REQUEST");return 2;}
            try(var session=factory.open()){
                String variable=plan.route()==Route.PORTAL?"EODIISSEO_PROBE_PORTAL_KEY":"EODIISSEO_PROBE_HUB_KEY";
                var probe=new EarthquakeApiProbe(session,new Credentials(Map.of(plan.route(),()->environment.apply(variable))),clock);
                var observation=probe.observe(plan,authorization);output.println(metadataJson(observation));
                return observation.conditions().equals(List.of("INCIDENT_AND_COVERAGE_UNVERIFIED"))?0:3;
            }
        }catch(Exception rejected){output.println("PROBE_REJECTED_NO_DIAGNOSTIC_DETAILS");return 2;}
    }
    private static Plan plan(Map<String,String> p){return new Plan(Route.valueOf(p.get("route")),LocalDate.parse(p.get("from")),LocalDate.parse(p.get("to")),Integer.parseInt(p.get("page")),Integer.parseInt(p.get("rows")),Format.valueOf(p.get("format")));}
    private static Authorization authorization(Path path)throws Exception{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>8192)throw new IllegalArgumentException();
        byte[] bytes;try(var in=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(8193);}if(bytes.length>8192)throw new IllegalArgumentException();
        var props=new LinkedHashMap<String,String>();for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")){
            line=line.strip();if(line.isBlank()||line.startsWith("#"))continue;int split=line.indexOf('=');if(split<1)throw new IllegalArgumentException();
            String key=line.substring(0,split).strip(),value=line.substring(split+1).strip();if(!FIELDS.contains(key)||props.putIfAbsent(key,value)!=null)throw new IllegalArgumentException();
        }
        if(!props.keySet().equals(FIELDS)||!"NONE".equals(props.get("body-storage")))throw new IllegalArgumentException();
        var p=plan(props);if(p.page()!=1||p.rows()!=1)throw new IllegalArgumentException("Initial probe approval only");
        return new Authorization(p,Instant.parse(props.get("expires-at")),Instant.parse(props.get("key-valid-until")),Integer.parseInt(props.get("max-requests")),yes(props,"development-api-approved"),yes(props,"allowed-ip-approved"),yes(props,"remaining-quota-approved"),yes(props,"metadata-access-approved"),props.get("approval-reference"));
    }
    private static boolean yes(Map<String,String> p,String key){if(!Set.of("true","false").contains(p.get(key)))throw new IllegalArgumentException();return "true".equals(p.get(key));}
}