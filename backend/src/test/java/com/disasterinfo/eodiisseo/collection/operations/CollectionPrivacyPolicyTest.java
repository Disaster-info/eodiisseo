package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.execution.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class CollectionPrivacyPolicyTest {
    final ReviewedRawPrivacyDetector detector=new ReviewedRawPrivacyDetector(Set.of("loc"),"a".repeat(64));
    CollectionArchivePolicy policy(){return new CollectionArchivePolicy(Duration.ofSeconds(3600),"OPS:REVIEWED",Set.of("SYNTHETIC-SECRET"),detector);}
    @Test void unreviewedTextFieldsAreOmittedWithoutInventedRedaction(){byte[] raw="{\"person\":\"synthetic name\"}".getBytes(StandardCharsets.UTF_8);var body=policy().inspect(raw);assertTrue(body.omitted());assertNull(body.bytes());assertEquals(CollectionAssurance.hash(new String(raw,StandardCharsets.UTF_8)),body.sha256());}
    @Test void reviewedPublicFieldCanBeRetained(){var body=policy().inspect("{\"loc\":\"의정부시\",\"lat\":37.1}".getBytes(StandardCharsets.UTF_8));assertFalse(body.omitted());}
    @Test void phoneEmailAndResidentNumberTripwiresOmitWholeBody(){for(String value:List.of("010-1234-5678","synthetic@example.invalid","901231-1234567")){var body=policy().inspect(("{\"loc\":\""+value+"\"}").getBytes(StandardCharsets.UTF_8));assertTrue(body.omitted());assertNull(body.bytes());}}
    @Test void escapedSecretsAndUnknownFormatsAreNotPassedAsPublicOriginals(){assertTrue(policy().inspect("{\"loc\":\"SYNTHETIC-SECRET\"}".getBytes(StandardCharsets.UTF_8)).omitted());assertTrue(policy().inspect("<xml>unreviewed</xml>".getBytes(StandardCharsets.UTF_8)).omitted());}
    @Test void detectorFailureFailsClosed(){CollectionRawPrivacyPort broken=new CollectionRawPrivacyPort(){public String fingerprint(){return "a".repeat(64);}public String reviewFingerprint(){return "b".repeat(64);}public Decision inspect(String text){throw new IllegalStateException("SYNTHETIC failure");}};assertTrue(new CollectionArchivePolicy(Duration.ofHours(1),"OPS:REVIEW",Set.of(),broken).inspect("{}".getBytes(StandardCharsets.UTF_8)).omitted());}
}