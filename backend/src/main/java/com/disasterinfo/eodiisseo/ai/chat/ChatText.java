package com.disasterinfo.eodiisseo.ai.chat;

import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Unicode whitespace validation that preserves the original question and official text. */
public final class ChatText {
    private static final String LINE_BREAK = "(?>\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])";
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile(
            LINE_BREAK + "[\\p{Zs}\\t]*" + LINE_BREAK + "(?:[\\p{Zs}\\t]*" + LINE_BREAK + ")*");

    private ChatText() {
    }

    public static boolean hasText(String text) {
        return text != null && text.codePoints().anyMatch(codePoint ->
                !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint));
    }

    public static Stream<String> paragraphs(String text) {
        return PARAGRAPH_BOUNDARY.splitAsStream(text).filter(ChatText::hasText);
    }
}
