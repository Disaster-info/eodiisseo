package com.disasterinfo.eodiisseo.ai.ollama;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.disasterinfo.eodiisseo.ai.ollama.OllamaException.Reason.*;

/** Bounded, duplicate-key/trailing-document rejecting JSON for untrusted model output. */
public final class OllamaJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private OllamaJson() {}

    public static String write(Object input) {
        try { return MAPPER.writeValueAsString(input); }
        catch (RuntimeException failure) { throw new OllamaException(INVALID_INPUT); }
    }

    public static JsonNode readObject(String input) {
        if (input == null || input.length() > 1_048_576) throw new OllamaException(INVALID_RESPONSE);
        try {
            JsonNode node = MAPPER.readValue(input, JsonNode.class);
            if (node == null || !node.isObject()) throw new OllamaException(INVALID_RESPONSE);
            return node;
        } catch (RuntimeException failure) { throw new OllamaException(INVALID_RESPONSE); }
    }
}
