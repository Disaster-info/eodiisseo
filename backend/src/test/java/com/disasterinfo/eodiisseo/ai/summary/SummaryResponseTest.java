package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.dto.SummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SummaryResponseTest {
    private static final UUID DISASTER = UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919");
    private static final Instant GENERATED_AT = Instant.parse("2026-09-28T04:06:00Z");

    @Autowired
    private JsonMapper mapper;

    @Test
    void exactApi521ShapeHasUuidStringAndNoOfficialSeverityOverride() {
        var response = new SummaryResponse(DISASTER, SummaryStatus.COMPLETED, "Supported AI summary.", GENERATED_AT);
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree("""
                {
                  "disasterId":"bd8da281-d393-5f8b-9813-a6707b2d0919",
                  "status":"COMPLETED",
                  "summary":"Supported AI summary.",
                  "generatedAt":"2026-09-28T04:06:00Z"
                }
                """));
    }

    @ParameterizedTest
    @EnumSource(value = SummaryStatus.class, names = {"PENDING", "FAILED", "STALE"})
    void incompleteOrStaleResultsNeverExposeTextOrGenerationTime(SummaryStatus status) {
        var response = new SummaryResponse(DISASTER, status, "Previous summary must be hidden.", GENERATED_AT);
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.get("status").asString()).isEqualTo(status.name());
        assertThat(json.get("summary").isNull()).isTrue();
        assertThat(json.get("generatedAt").isNull()).isTrue();
        assertThat(json.size()).isEqualTo(4);
    }

    @Test
    void completedResponseCannotInventTextOrTimestamp() {
        assertThatThrownBy(() -> new SummaryResponse(DISASTER, SummaryStatus.COMPLETED, null, GENERATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryResponse(DISASTER, SummaryStatus.COMPLETED, "Supported text", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
