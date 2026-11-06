package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatRetentionPolicy;
import java.time.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Development decision: canonical ERD created_at + 2160 hours, using UTC Instant. */
@Component @Profile("!test")
public class CreatedAtRetentionPolicy implements ChatRetentionPolicy {
    public boolean isApproved(){return true;}
    public Instant approvedExpiresAt(Instant requested,Instant completed) {
        if(requested==null || completed==null || completed.isBefore(requested))throw new IllegalArgumentException("Invalid retention times");
        return requested.plus(Duration.ofDays(90));
    }
}
