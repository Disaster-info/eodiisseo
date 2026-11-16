package com.disasterinfo.eodiisseo.disaster.collection;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** Bounded GET transport, no redirects/proxies/logging; timeout covers headers and entire body. */
public final class OfficialHttp implements AutoCloseable {
    public record Reply(int status, byte[] body) {
        public Reply {body=body.clone();}
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "OfficialReply[status="+status+", body=redacted]";}
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
            public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}
        }).build();
    public record HeadersReply(int status,byte[] body,List<String> retryAfter,List<String> contentTypes){
        public HeadersReply(int status,byte[] body,List<String> retryAfter){this(status,body,retryAfter,List.of());}
        public HeadersReply {body=body.clone();retryAfter=List.copyOf(retryAfter);contentTypes=List.copyOf(contentTypes);}
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "OfficialHeadersReply[status="+status+",headers=redacted,body=redacted]";}
    }
    public Reply get(URI uri,Duration timeout){var response=getWithHeaders(uri,timeout);return new Reply(response.status(),response.body());}
    public HeadersReply getWithHeaders(URI uri,Duration timeout) {
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new CollectionFailure("INVALID_TIMEOUT");
        var future=http.sendAsync(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept","application/json, application/xml, text/html").GET().build(),
            info->new LimitedBody());
        try {
            var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            return new HeadersReply(response.statusCode(),response.body(),response.headers().allValues("Retry-After"),response.headers().allValues("Content-Type"));
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new CollectionFailure("INTERRUPTED");}
          catch(TimeoutException e){throw new CollectionFailure("TIMEOUT");}
          catch(Exception e){throw new CollectionFailure("TRANSPORT");}
        finally {if(!future.isDone())future.cancel(true);}
    }
    public void close(){http.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>2_097_152-bytes.size()){subscription.cancel();result.completeExceptionally(new CollectionFailure("BODY_LIMIT"));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(new CollectionFailure("TRANSPORT"));}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
