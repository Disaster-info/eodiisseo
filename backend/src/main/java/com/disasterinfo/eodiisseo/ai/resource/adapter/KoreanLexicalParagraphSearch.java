package com.disasterinfo.eodiisseo.ai.resource.adapter;

import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceParagraphSearch;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative Korean synonym retrieval, with complete original paragraphs retained verbatim. */
@Component
public class KoreanLexicalParagraphSearch implements ResourceParagraphSearch {
    private static final String BREAK = "(?>\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])";
    private static final Pattern BOUNDARY = Pattern.compile(BREAK + "[\\p{Zs}\\t]*" + BREAK + "(?:[\\p{Zs}\\t]*" + BREAK + ")*");
    private static final List<Topic> TOPICS = List.of(
            new Topic("EARTHQUAKE", List.of("지진", "흔들", "진동"), List.of("지진", "땅이흔들", "바닥이흔들", "집이흔들", "건물이흔들", "흔들림")),
            new Topic("HEAVY_RAIN", List.of("호우", "침수", "폭우"), List.of("호우", "폭우", "침수", "홍수", "비가많이", "비가너무", "도로가잠", "물이차")),
            new Topic("WILDFIRE", List.of("산불", "산림", "산에서불"), List.of("산불", "산에불", "산에서불", "산림화재")));
    private static final List<List<String>> FACETS = List.of(
            List.of("탁자", "책상", "실내", "집안", "집에서", "건물안"),
            List.of("실외", "밖에서", "야외", "건물밖"),
            List.of("차량", "자동차", "운전", "차안"),
            List.of("도로", "지하차도", "침수된길", "물에잠긴길"),
            List.of("대피", "피난", "피하"));
    private static final List<String> ACTION = List.of("해야", "행동", "대처", "대응", "안전", "어떻게", "대피", "피하", "요령", "방법", "어디");
    private static final List<String> GUIDANCE = List.of("하세요", "하십시오", "합니다", "한다", "마세요", "않", "대피", "보호", "피하", "이동", "멈추", "기다", "숙이", "확인", "진입", "행동");
    private static final List<String> DYNAMIC = List.of("실시간", "지금", "현재", "오늘", "우리동네", "우리집", "내집", "내위치", "근처", "여기", "이곳", "위험한가", "위험이있", "위험여부", "안전한지", "안전한가", "안전할까", "안전여부", "발생했", "발생여부", "피해액", "발생시각", "예상피해", "경로", "좌표", "확률");
    private static final Set<String> QUESTION_WORDS = Set.of("나", "나면", "날", "났을", "일어나면", "때", "시", "동안", "발생", "발생시", "경우", "상황", "안전", "요령", "행동", "행동요령", "대처", "대응", "방법", "어떻게", "어디", "무엇", "뭐", "왜", "많이", "너무", "와서", "하", "해", "해야", "하나", "하나요", "해요", "하는", "하면", "할", "있", "있나", "좋", "알려줘", "알려주세요", "주세요", "해줘", "해주세요");
    private static final List<String> SUFFIXES = List.of("에서는", "으로는", "에게는", "하려면", "하세요", "하는", "하나요", "해야", "하면", "에서", "으로", "에게", "에는", "에선", "일까요", "할까요", "인가요", "나요", "은", "는", "이", "가", "을", "를", "의", "에", "도", "로", "와", "과");
    private static final List<String> INJECTION = List.of("ignoreprevious", "ignoresystem", "systemprompt", "system:", "developer:", "assistant:",
            "[inst]", "<|system", "지시를무시", "지시무시", "명령을무시", "시스템명령", "시스템프롬프트", "개발자지시", "이전지침을무시");

    @Override
    public Optional<Query> prepare(String question) {
        if (question == null || question.isBlank() || question.length() > 10_000 || suspicious(question)) return Optional.empty();
        String text = normalized(question);
        if (containsAny(text, DYNAMIC) || !containsAny(text, ACTION)) return Optional.empty();
        List<Topic> matches = TOPICS.stream().filter(topic -> containsAny(text, topic.aliases())).toList();
        if (matches.size() != 1) return Optional.empty();
        // Every requested situation must be supported by a complete matching paragraph.
        List<String> facets = FACETS.stream().filter(group -> containsAny(text, group)).flatMap(List::stream).distinct().toList();
        Topic topic = matches.getFirst();
        List<String> required = meaningfulTerms(question, topic);
        if (required.size() > 12) return Optional.empty();
        return Optional.of(new Query(topic.code(), topic.terms(), facets, required));
    }

    @Override
    public List<String> select(Query query, OfficialResourceDocument document) {
        if (query == null || document == null || document.getContentText() == null
                || document.getContentText().length() > 200_000 || suspicious(document.getContentText())) return List.of();
        Topic topic = TOPICS.stream().filter(value -> value.code().equals(query.topic())).findFirst().orElse(null);
        if (topic == null || !containsAny(normalized(document.getTitle() + " " + document.getContentText()), topic.terms())) return List.of();
        // No section hierarchy exists in this ERD. A multi-hazard title cannot safely supply context
        // to otherwise unscoped paragraphs; an explicitly scoped publication is required instead.
        if (TOPICS.stream().anyMatch(other -> !other.code().equals(topic.code())
                && containsAny(normalized(document.getTitle()), other.aliases()))) return List.of();
        List<String> selected = new ArrayList<>();
        for (String paragraph : BOUNDARY.split(document.getContentText())) {
            if (paragraph.isBlank() || paragraph.length() > 4_000) continue;
            String text = normalized(paragraph);
            // Do not select an unrelated hazard paragraph from a multi-hazard document.
            if (TOPICS.stream().anyMatch(other -> !other.code().equals(topic.code()) && containsAny(text, other.aliases()))) continue;
            if (!containsAny(text, topic.terms()) && !topicContext(topic.code(), text)) continue;
            if (!containsAny(text, GUIDANCE)) continue;
            if (!query.facetTerms().isEmpty() && !containsAny(text, query.facetTerms())) continue;
            // Apply specific intent before the result cap: an early generic paragraph must not
            // displace a later paragraph that actually answers the requested situation/action.
            // Requiring all specific terms within one complete paragraph is deliberately conservative.
            if (query.requiredTerms().stream().anyMatch(term -> !text.contains(term))) continue;
            selected.add(paragraph);
            if (selected.size() == 3) break;
        }
        // Multiple requested facets cannot be satisfied by just any one of their aliases.
        for (List<String> facet : FACETS) {
            if (query.facetTerms().stream().anyMatch(facet::contains)
                    && selected.stream().noneMatch(paragraph -> containsAny(normalized(paragraph), facet))) return List.of();
        }
        if (query.requiredTerms().stream().anyMatch(term -> selected.stream().noneMatch(paragraph -> normalized(paragraph).contains(term)))) return List.of();
        return List.copyOf(selected);
    }

    private static boolean topicContext(String topic, String text) {
        return containsAny(text, switch (topic) {
            case "EARTHQUAKE" -> List.of("탁자", "책상", "흔들", "낙하물", "머리를보호");
            case "HEAVY_RAIN" -> List.of("하천", "지하차도", "침수", "범람", "급류");
            case "WILDFIRE" -> List.of("산불", "산림", "연기", "불길");
            default -> List.of();
        });
    }

    /** Keep specific user intent instead of assuming every question about a hazard has the same answer. */
    private static List<String> meaningfulTerms(String question, Topic topic) {
        List<String> result = new ArrayList<>();
        var matcher = Pattern.compile("[\\p{L}\\p{N}]+").matcher(Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            for (int pass = 0; pass < 2; pass++) {
                for (String suffix : SUFFIXES) {
                    if (term.endsWith(suffix) && term.length() > suffix.length()) { term = term.substring(0, term.length() - suffix.length()); break; }
                }
            }
            if (term.length() < 2 || QUESTION_WORDS.contains(term)
                    || term.matches("(행동|대처|대응|알려|어떻|해야|하나|할까|되나|될까).*")) continue;
            String value = term;
            if (topic.aliases().stream().anyMatch(alias -> alias.contains(value))
                    || topic.terms().stream().anyMatch(value::startsWith)) continue;
            if (topic.code().equals("EARTHQUAKE") && value.startsWith("흔들")) continue;
            if (topic.code().equals("HEAVY_RAIN") && (value.startsWith("잠겼") || value.startsWith("잠긴"))) continue;
            // These situation synonyms are checked as groups above (e.g. 운전 -> 자동차).
            if (FACETS.stream().anyMatch(group -> group.contains(value))) continue;
            if (!result.contains(value)) result.add(value);
        }
        return List.copyOf(result);
    }

    public static boolean suspicious(String text) { return text != null && containsAny(normalized(text), INJECTION); }
    private static boolean containsAny(String text, List<String> terms) { return terms.stream().anyMatch(term -> text.contains(normalized(term))); }
    private static String normalized(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private record Topic(String code, List<String> terms, List<String> aliases) { }
}
