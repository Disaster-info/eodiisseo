package audit.collection.fixture;
/** One loopback synthetic HTTP exchange. No credentials, approved providers or database access. */
public final class HttpRetryProbe {
    public static void main(String[] args){try(var http=new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp()){
        try{http.getWithHeaders(java.net.URI.create("http://127.0.0.1:"+Integer.parseInt(args[0])+"/synthetic"),java.time.Duration.ofSeconds(3));throw new IllegalStateException("Expected uncertain connection");}
        catch(com.disasterinfo.eodiisseo.disaster.collection.CollectionFailure expected){System.out.println("EXPECTED_UNCERTAIN");}
    }}
}