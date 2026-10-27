package com.disasterinfo.eodiisseo.ai.chat;

/** Unicode whitespace validation that preserves the original question and official text. */
public final class ChatText {

    private ChatText() {
    }

    public static boolean hasText(String text) {
        return text != null && text.codePoints().anyMatch(codePoint ->
                !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint));
    }
}
