package com.disasterinfo.eodiisseo.ai.resource;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GuidanceCatalogAuditTest {
    String fixture() throws Exception {
        try(var input=getClass().getResourceAsStream("/ai/resource/synthetic-guidance-source-catalog.json")){
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void metadataOnlyContractRemainsAuditableWithoutPrivateCatalog() throws Exception {GuidanceCatalogAudit.validate(fixture());}
    @Test void approvalCannotBeIntroducedIntoAuditInput() throws Exception {
        var changed=fixture().replace("PENDING","APPROVED");
        assertThrows(IllegalArgumentException.class,()->GuidanceCatalogAudit.validate(changed));
    }
    @Test void contentFingerprintCannotBeIntroducedIntoAuditInput() throws Exception {
        var changed=fixture().replace("\"contentFingerprint\":  null","\"contentFingerprint\": \""+"a".repeat(64)+"\"");
        assertNotEquals(fixture(),changed,"Fixture replacement must exercise the content-hash guard");
        assertThrows(IllegalArgumentException.class,()->GuidanceCatalogAudit.validate(changed));
    }
}