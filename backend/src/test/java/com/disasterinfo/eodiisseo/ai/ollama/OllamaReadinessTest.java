package com.disasterinfo.eodiisseo.ai.ollama;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class OllamaReadinessTest {
    @Test void installedModelMustMatchConfiguredNameWithoutGuessingOrPulling()throws Exception{
        var body=new AtomicReference<>("{\"models\":[{\"name\":\"fixture:exact\"}]}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/tags",exchange->{byte[] raw=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,raw.length);try(var out=exchange.getResponseBody()){out.write(raw);}});
        server.start();
        var settings=new OllamaSettings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture:exact",Duration.ofSeconds(2),Duration.ofSeconds(1),Set.of("127.0.0.1"));
        try(var client=new OllamaChatClient(settings)){
            assertThat(client.verifyInstalledModel()).isTrue();
            body.set("{\"models\":[{\"name\":\"different:exact\"}]}");
            assertThatThrownBy(client::verifyInstalledModel).isInstanceOfSatisfying(OllamaException.class,e->assertThat(e.reason()).isEqualTo(OllamaException.Reason.MODEL_NOT_FOUND));
            body.set("{\"models\":null}");
            assertThatThrownBy(client::verifyInstalledModel).isInstanceOf(OllamaException.class);
        }finally{server.stop(0);}
    }
}
