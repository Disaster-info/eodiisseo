package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Classify locally into a closed vocabulary; zero raw input characters are copied outbound. */
@Component @Profile("!test")
public class LocalIntentPrivacy implements ChatPromptPrivacyPort {
    private final ChatOutboundConsentPort consent;
    private static final Pattern INSTRUCTION=Pattern.compile("(?iu)https?://|ignore|system|prompt|instruction|password|bearer|token|\\bsk-|비밀번호|토큰|시스템|명령|지시");
    private static final Pattern ACTION=Pattern.compile("어떻게|행동|대피|대처|안전|방법|요령|해야|피하");
    private static final Pattern DYNAMIC=Pattern.compile("지금|현재|실시간|위험한가|안전한가|경로|대피소|발생했|규모|진도");
    private static final Map<String,String> INTENTS=Map.of(
        "지진","지진 발생 시 안전한 대피 방법",
        "호우","호우 발생 시 안전한 대피 방법",
        "산불","산불 발생 시 안전한 대피 방법",
        "태풍","태풍 발생 시 안전한 대피 방법",
        "홍수","홍수 발생 시 안전한 대피 방법");
    public LocalIntentPrivacy(ChatOutboundConsentPort consent){this.consent=consent;}
    public Optional<PreparedQuestion> minimizeLocally(String raw){return Optional.empty();}
    @Override public Optional<PreparedQuestion> minimizeLocally(UUID member,String raw) {
        if(member==null || raw==null || raw.length()>2000 || raw.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')
                || INSTRUCTION.matcher(raw).find() || DYNAMIC.matcher(raw).find() || !ACTION.matcher(raw).find() || !consent.permits(member))return Optional.empty();
        var intents=INTENTS.entrySet().stream().filter(e->raw.contains(e.getKey())).toList();
        if(intents.size()!=1)return Optional.empty();
        return Optional.of(new PreparedQuestion(intents.getFirst().getValue(),true));
    }
}
