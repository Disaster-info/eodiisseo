package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Closed local vocabulary only. Names/phones/addresses/raw substrings are never copied outbound. */
@Component @Profile("!test")
public class LocalIntentPrivacy implements ChatPromptPrivacyPort {
    private final ChatOutboundConsentPort consent;
    private static final Pattern INSTRUCTION=Pattern.compile("(?iu)https?://|ignore|system|prompt|instruction|password|bearer|token|\\bsk-|비밀번호|토큰|시스템|명령|지시");
    private static final Pattern ACTION=Pattern.compile("어떻게|행동|대피|대처|안전|방법|요령|해야|피하|이용해도|접근해도|진입해도|주의|예방");
    private static final Pattern DYNAMIC=Pattern.compile("지금|현재|실시간|위험한가|위험여부|위험이있|안전한가|안전한지|안전여부|경로|대피소|발생했|규모|진도|우리동네|내위치|근처|좌표|확률|오늘");
    private static final Map<String,List<String>> HAZARDS=Map.of("지진",List.of("지진","흔들"),"호우",List.of("호우","폭우","침수"),"산불",List.of("산불","산림화재"),"태풍",List.of("태풍"),"홍수",List.of("홍수"));
    private static final Map<String,List<String>> FACETS=Map.of("실내",List.of("실내","집안"),"엘리베이터",List.of("엘리베이터","승강기"),"지하차도",List.of("지하차도"),"지하공간",List.of("지하공간","지하실","지하주차장"),"실외",List.of("실외","야외","밖에서","건물밖"),"연기",List.of("연기"),"예방",List.of("예방","발생전"),"대피",List.of("대피","피난","피하"));
    public LocalIntentPrivacy(ChatOutboundConsentPort consent){this.consent=consent;}
    public boolean localPersistenceRevoked(UUID member){return consent.localPersistenceRevoked(member);}
    public Optional<PreparedQuestion> minimizeLocally(String raw){return Optional.empty();}
    public Optional<PreparedQuestion> minimizeLocally(UUID member,String raw){
        if(member==null||raw==null||raw.length()>2000||raw.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')||INSTRUCTION.matcher(raw).find())return Optional.empty();
        String text=raw.replaceAll("발생했을\\s*때","발생 시").replaceAll("\\s+","");
        if(DYNAMIC.matcher(text).find()||!ACTION.matcher(text).find()||!consent.permits(member))return Optional.empty();
        var hazards=HAZARDS.entrySet().stream().filter(e->e.getValue().stream().anyMatch(text::contains)).map(Map.Entry::getKey).toList();if(hazards.size()!=1)return Optional.empty();
        String facets=FACETS.entrySet().stream().filter(e->e.getValue().stream().anyMatch(text::contains)).map(Map.Entry::getKey).sorted().collect(java.util.stream.Collectors.joining(" "));
        String safe=facets.isEmpty()||facets.equals("대피")?hazards.getFirst()+" 발생 시 안전한 대피 방법":hazards.getFirst()+" "+facets+" 행동요령";
        return Optional.of(new PreparedQuestion(safe,true));
    }
}