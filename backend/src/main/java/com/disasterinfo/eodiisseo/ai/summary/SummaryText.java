package com.disasterinfo.eodiisseo.ai.summary;

/** Shared summary-boundary validation; preserves meaningful original and generated text verbatim. */
public final class SummaryText {
    private SummaryText() { }

    public static boolean hasContent(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
                !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint));
    }
}
