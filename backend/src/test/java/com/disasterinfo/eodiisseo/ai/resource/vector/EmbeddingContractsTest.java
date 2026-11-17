package com.disasterinfo.eodiisseo.ai.resource.vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class EmbeddingContractsTest {
    final EmbeddingPort.Model model=new EmbeddingPort.Model("SYNTHETIC", "sha256:"+"a".repeat(64),2,128,"SYNTHETIC-FORMAT-1");
    EmbeddingPort.Request request(){return new EmbeddingPort.Request(model,EmbeddingPort.Purpose.QUERY,"SYNTHETIC PRIVATE QUESTION",false,Instant.now().plusSeconds(1));}
    EmbeddingPort fake(java.util.function.Function<EmbeddingPort.Request,EmbeddingPort.Result> response){return new EmbeddingPort(){public boolean networkFree(){return true;}public Result embed(Request r){return response.apply(r);}};}
    @Test void validUnitVectorIsDefensivelyCopiedAndBindsTheCompleteModelContract(){
        var request=request();double[] value={1,0};var result=new EmbeddingPort.Result(model,EmbeddingPort.sha(request.text()),4,true,value);value[0]=9;
        assertThat(EmbeddingPort.generate(fake(r->result),request,Clock.systemUTC())).containsExactly(1,0);
        var copy=result.values();copy[0]=3;assertThat(result.values()).containsExactly(1,0);
        assertThat(request.toString()).doesNotContain(request.text());assertThat(result.toString()).doesNotContain("1.0");
    }
    @ParameterizedTest @ValueSource(strings={"DIMENSION","NAN","INFINITY","ZERO","NON_UNIT","OVERFLOW"})
    void malformedVectorsAreNeverRepairedIntoSuccess(String mode){
        double[] v=switch(mode){case "DIMENSION"->new double[]{1};case "NAN"->new double[]{Double.NaN,0};case "INFINITY"->new double[]{Double.POSITIVE_INFINITY,0};case "ZERO"->new double[]{0,0};case "NON_UNIT"->new double[]{2,0};default->new double[]{Double.MAX_VALUE,1};};
        assertThatThrownBy(()->EmbeddingPort.vector(model,v)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"DIGEST","ID","FORMAT","DIMENSION","INPUT","TOKENS","TRUNCATED"})
    void staleOrUnverifiedProviderContractIsRejected(String mode){
        var port=fake(r->{
            var observed=switch(mode){case "DIGEST"->new EmbeddingPort.Model(model.id(),"sha256:"+"b".repeat(64),2,128,model.inputVersion());case "ID"->new EmbeddingPort.Model("OTHER",model.digest(),2,128,model.inputVersion());case "FORMAT"->new EmbeddingPort.Model(model.id(),model.digest(),2,128,"OTHER");case "DIMENSION"->new EmbeddingPort.Model(model.id(),model.digest(),3,128,model.inputVersion());default->model;};
            return new EmbeddingPort.Result(observed,mode.equals("INPUT")?"wrong":EmbeddingPort.sha(r.text()),mode.equals("TOKENS")?129:3,!mode.equals("TRUNCATED"),new double[]{1,0});
        });
        assertThatThrownBy(()->EmbeddingPort.generate(port,request(),Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timeoutCancelsRatherThanWaitingForSlowProvider(){
        EmbeddingPort port=new EmbeddingPort(){public boolean networkFree(){return true;}public Result embed(Request r)throws Exception{Thread.sleep(5000);throw new AssertionError();}};
        var r=new EmbeddingPort.Request(model,EmbeddingPort.Purpose.DOCUMENT,"SYNTHETIC",false,Instant.now().plusMillis(50));
        long start=System.nanoTime();assertThatThrownBy(()->EmbeddingPort.generate(port,r,Clock.systemUTC())).hasMessage("EMBEDDING_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void networkPortAndTruncatingRequestsAreDisabled(){
        EmbeddingPort port=r->{throw new AssertionError("No call allowed");};
        assertThatThrownBy(()->EmbeddingPort.generate(port,request(),Clock.systemUTC())).hasMessage("NETWORK_EMBEDDING_DISABLED");
        assertThatThrownBy(()->new EmbeddingPort.Request(model,EmbeddingPort.Purpose.QUERY,"SYNTHETIC",true,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmbeddingPort.Model("SYNTHETIC","ac6da0dfba84",1024,32000,"unverified-tag")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mandatoryContextGuardRejectsModelSelectedSubsetEvenWhenItIsAnExactParagraph(){
        String a="SYNTHETIC condition",b="SYNTHETIC exception";UUID id=UUID.randomUUID();
        var resource=new com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.OfficialResource(id,"행정안전부","SYNTHETIC",java.net.URI.create("https://safekorea.go.kr/synthetic"),a+"\n\n"+b,true,true,true);
        var request=new com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GenerationRequest("SYNTHETIC",List.of(resource),Instant.now().plusSeconds(5));
        var text=new String[]{a};
        var delegate=new com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort(){public boolean isConfigured(){return true;}public GeneratedAnswer generate(GenerationRequest r){return new GeneratedAnswer(text[0],List.of(id),true,"SYNTHETIC");}};
        var guard=new MandatoryContextGenerationGuard(delegate);assertThat(guard.generate(request).grounded()).isFalse();
        text[0]=a+"\n"+b;assertThat(guard.generate(request).grounded()).isTrue();
        text[0]=b+"\n"+a;assertThat(guard.generate(request).grounded()).isFalse();
    }
}