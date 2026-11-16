package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
@Component @Profile("!test")
public class ChatRetentionWorker {
    private final ChatService chat;private final boolean enabled;
    public ChatRetentionWorker(ChatService chat,@Value("${eodiisseo.ai.retention-worker.enabled:${eodiisseo.retention.enabled:false}}") boolean enabled){this.chat=chat;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${eodiisseo.ai.retention-poll-ms:3600000}") public void tick(){if(enabled)chat.deleteExpiredAnswers();}
}
