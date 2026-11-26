// FS-CHAT-001: AI 재난 대응 안내 — 공식 행동요령 검색 → 근거가 있을 때만 AI 답변, 없으면 기본 행동요령
// C안: 남색 띠 위에 안내 문구, 내 질문은 남색 말풍선, AI 답변은 점선 쿠폰(공식 정보와 구분)
import { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, ScrollView, Text, TextInput, View, type ScrollViewInstance } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Badge, Btn, Chip, Header, Icon, useLayout, useTheme } from '../../components/ui';
import { AI_QUESTION_MAX, askGuidance, errText } from '../../data/server';

// retry = 실패한 답의 원래 질문 (다시 시도 버튼으로 같은 질문을 다시 보냄)
type Msg = { id: number; me: boolean; text: string; sources?: string[]; fallback?: boolean; failed?: boolean; retry?: string };
const SUGGEST = ['지진이 나면 어떻게 해요?', '집 앞이 침수되고 있어요', '산불 연기가 보여요'];


export default function ChatScreen() {
  const { C, s } = useTheme();
  const { gutter, column } = useLayout();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [q, setQ] = useState('');
  const [pending, setPending] = useState(false);
  const scroll = useRef<ScrollViewInstance>(null); // RN 0.88: ref 타입은 컴포넌트가 아닌 인스턴스 타입
  const { bottom } = useSafeAreaInsets();
  const alive = useRef(true); // 화면을 떠난 뒤 도착한 답은 버림
  useEffect(() => { // 다시 붙을 때(개발 모드 재실행·Fast Refresh)도 켜 둠 — 꺼진 채 남으면 이후 답을 모두 버림
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  // 중복 요청 방지: 상태값(pending)은 다음 그리기 때 바뀌어 빠른 연속 탭·Enter 가 둘 다 통과할 수 있음 → ref 로 즉시 막음
  const busy = useRef(false);
  // retryOf = 다시 시도한 실패 답의 id (그 답의 버튼을 없애고, 질문 말풍선은 다시 붙이지 않음)
  const ask = (text: string, retryOf?: number) => {
    const t = text.trim().slice(0, AI_QUESTION_MAX);
    if (!t || busy.current) return;
    busy.current = true;
    if (retryOf === undefined) { setMsgs(m => [...m, { id: Date.now(), me: true, text: t }]); setQ(''); }
    else setMsgs(m => m.map(x => (x.id === retryOf ? { ...x, retry: undefined } : x)));
    setPending(true);
    // 서버가 공식 자료를 검색해 근거가 있을 때만 AI 답변, 없으면 기본 행동요령(FALLBACK). 응답 제한 60초 (askGuidance)
    askGuidance(t).then(a => ({ id: Date.now(), me: false, ...a }), e => ({ id: Date.now(), me: false, failed: true, fallback: true, retry: t,
      text: `답변을 받지 못했어요. (${errText(e)})\n위급하면 바로 119에 신고하고 공식 재난문자·방송 안내를 따르세요.` }))
      .then((a: Msg) => {
        busy.current = false;
        if (!alive.current) return;
        setMsgs(m => [...m, a]);
        AccessibilityInfo.announceForAccessibility(a.failed ? '답변을 받지 못했어요' : a.fallback ? '기본 행동요령을 안내했어요' : '행동요령 답변이 도착했어요');
        setPending(false);
      });
  };

  return (
    // 키보드 애니메이션과 같은 프레임으로 아래 여백을 늘려 입력창을 키보드 바로 위에 붙임 (iOS·안드로이드 동일)
    // automaticOffset: 헤더 높이 자동 보정. -bottom: 키보드가 하단 바를 덮으므로 그 여백만큼 덜 올림
    <KeyboardAvoidingView style={s.fill} behavior="padding" automaticOffset keyboardVerticalOffset={-bottom}>
      <Header title="재난 행동요령 AI" />
      <ScrollView ref={scroll} bounces={false} overScrollMode="never" contentInsetAdjustmentBehavior="automatic" onContentSizeChange={() => scroll.current?.scrollToEnd()} contentContainerStyle={[column, { paddingVertical: 16, paddingHorizontal: gutter, gap: 12 }]}>
        {/* 헤더와 이어지는 상단 띠 (위로 당겨도 남색이 이어지게 위쪽으로 길게). 아래 끝은 안내 문구 줄이 직접 칠함 (줄 수가 바뀌어도 맞게) */}
        <View style={{ position: 'absolute', left: 0, right: 0, top: -1000, height: 1000, backgroundColor: C.head }} />
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center', backgroundColor: C.head, marginHorizontal: -gutter, paddingHorizontal: gutter, marginTop: -16, paddingTop: 16, paddingBottom: 16 }}>
          <Icon name="information-circle" size={18} color={C.headSub} />
          <Text style={[s.muted, { color: C.headSub, flex: 1, marginTop: 0 }]}>행정안전부·소방청·기상청·산림청 공식 자료를 근거로 AI가 안내해요. 위급하면 바로 신고하세요.</Text>
        </View>
        {msgs.length === 0 && (
          <View style={[s.row, { flexWrap: 'wrap' }]}>{SUGGEST.map(x => <Chip key={x} text={x} on={false} onPress={() => ask(x)} />)}</View>
        )}
        {msgs.map(m => m.me ? (
          <View key={m.id} style={{ alignSelf: 'flex-end', maxWidth: '85%', backgroundColor: C.navy, borderRadius: 16, borderBottomRightRadius: 4, padding: 12 }}>
            <Text style={[s.body, { color: C.onSign }]} lineBreakStrategyIOS="hangul-word">{m.text}</Text>
          </View>
        ) : (
          <View key={m.id} style={[s.card, { maxWidth: '92%', borderWidth: 1.5, borderStyle: 'dashed', borderColor: m.fallback ? C.perf : C.tabOn }]}>
            <View style={{ alignSelf: 'flex-start', marginBottom: 6 }}>
              <Badge text={m.failed ? '응답 실패' : m.fallback ? '기본 행동요령' : 'AI 생성 안내'} icon={m.failed ? 'cloud-offline-outline' : m.fallback ? 'book-outline' : 'sparkles'} color={m.failed ? C.danger : m.fallback ? C.sub : C.tabOn} />
            </View>
            <Text style={s.body} lineBreakStrategyIOS="hangul-word">{m.text}</Text>
            {!!m.retry && <View style={{ marginTop: 8 }}><Btn title="다시 시도" icon="refresh" color={C.navy} onPress={() => ask(m.retry!, m.id)} /></View>}
            {m.sources?.map(src => (
              <View key={src} style={[s.row, { marginTop: 6 }]}>
                <Icon name="document-text-outline" size={14} color={C.sub} />
                <Text style={[s.muted, { marginTop: 0 }]}>출처: {src}</Text>
              </View>
            ))}
          </View>
        ))}
        {pending && <View style={s.row}><ActivityIndicator color={C.tabOn} /><Text style={s.muted}>공식 자료를 찾고 있어요…</Text></View>}
      </ScrollView>
      <View style={{ paddingTop: 12, paddingHorizontal: Math.max(12, gutter), paddingBottom: 12 + bottom, backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.border }}>
        {/* 입력줄도 대화와 같은 폭 칸에 (바 배경은 화면 끝까지) */}
        <View style={[s.row, column]}>
        <TextInput accessibilityLabel="재난 대응 질문" style={[s.input, { flex: 1, marginVertical: 0 }]} placeholder="재난 대응 방법을 물어보세요" placeholderTextColor={C.sub}
          value={q} onChangeText={setQ} onSubmitEditing={() => ask(q)} returnKeyType="send" maxLength={AI_QUESTION_MAX} />
        <View style={{ width: 64 }}><Btn title="전송" color={C.navy} onPress={() => ask(q)} /></View>
        </View>
        {/* 제한에 가까워지면 남은 글자 수 안내 (서버도 2,000자를 넘으면 거절) */}
        {q.length > AI_QUESTION_MAX - 200 && <Text style={[s.muted, column, { textAlign: 'right' }]}>{q.length.toLocaleString()}/{AI_QUESTION_MAX.toLocaleString()}자</Text>}
      </View>
    </KeyboardAvoidingView>
  );
}
