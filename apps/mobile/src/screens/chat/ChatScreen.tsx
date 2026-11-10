// FS-CHAT-001: AI 재난 대응 안내 — 공식 행동요령 검색 → 근거가 있을 때만 AI 답변, 없으면 기본 행동요령
// C안: 남색 띠 위에 안내 문구, 내 질문은 남색 말풍선, AI 답변은 점선 쿠폰(공식 정보와 구분)
import { useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, ScrollView, Text, TextInput, View, type ScrollViewInstance } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Badge, Btn, Chip, Header, Icon, dial119, useLayout, useTheme } from '../../components/ui';
import { BASIC_GUIDE, GUIDES } from '../../data/mock';

type Msg = { id: number; me: boolean; text: string; sources?: string[]; fallback?: boolean };
const SUGGEST = ['지진이 나면 어떻게 해요?', '집 앞이 침수되고 있어요', '산불 연기가 보여요'];

// ponytail: 키워드 매칭으로 검색 흉내. 실제로는 서버에서 공식자료 검색 + AI 생성
function answer(q: string): Omit<Msg, 'id' | 'me'> {
  const hits = GUIDES.filter(g => g.keys.some(k => q.includes(k)));
  if (!hits.length) return { text: `질문과 관련된 공식 자료를 찾지 못했어요. 기본 행동요령을 안내합니다.\n\n${BASIC_GUIDE}`, fallback: true };
  return { text: hits.map(g => g.text).join('\n\n'), sources: hits.map(g => `${g.org} · ${g.title}`) };
}

export default function ChatScreen() {
  const { C, s } = useTheme();
  const { gutter, column } = useLayout();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [q, setQ] = useState('');
  const [pending, setPending] = useState(false);
  const scroll = useRef<ScrollViewInstance>(null); // RN 0.88: ref 타입은 컴포넌트가 아닌 인스턴스 타입
  const { bottom } = useSafeAreaInsets();

  const ask = (text: string) => {
    const t = text.trim();
    if (!t || pending) return;
    setMsgs(m => [...m, { id: Date.now(), me: true, text: t }]);
    setQ(''); setPending(true);
    setTimeout(() => { // AI 응답 흉내
      const a = answer(t);
      setMsgs(m => [...m, { id: Date.now(), me: false, ...a }]);
      AccessibilityInfo.announceForAccessibility(a.fallback ? '기본 행동요령을 안내했어요' : '행동요령 답변이 도착했어요');
      setPending(false);
    }, 700);
  };

  return (
    // 키보드 애니메이션과 같은 프레임으로 아래 여백을 늘려 입력창을 키보드 바로 위에 붙임 (iOS·안드로이드 동일)
    // automaticOffset: 헤더 높이 자동 보정. -bottom: 키보드가 하단 바를 덮으므로 그 여백만큼 덜 올림
    <KeyboardAvoidingView style={s.fill} behavior="padding" automaticOffset keyboardVerticalOffset={-bottom}>
      <Header title="재난 행동요령 AI" />
      <ScrollView ref={scroll} bounces={false} overScrollMode="never" contentInsetAdjustmentBehavior="automatic" onContentSizeChange={() => scroll.current?.scrollToEnd()} contentContainerStyle={[column, { paddingVertical: 16, paddingHorizontal: gutter, gap: 12 }]}>
        {/* 헤더와 이어지는 상단 띠 (위로 당겨도 남색이 이어지게 위쪽으로 길게) */}
        <View style={{ position: 'absolute', left: 0, right: 0, top: -1000, height: 1000 + 84, backgroundColor: C.head }} />
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center' }}>
          <Icon name="information-circle" size={18} color={C.headSub} />
          <Text style={[s.muted, { color: C.headSub, flex: 1, marginTop: 0 }]}>행정안전부·소방청·기상청·산림청 공식 자료를 근거로 AI가 안내해요. 위급하면 바로 신고하세요.</Text>
        </View>
        <Btn title="119 전화 걸기" icon="call" color={C.emergency} onPress={dial119} />
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
              <Badge text={m.fallback ? '기본 행동요령' : 'AI 생성 안내'} icon={m.fallback ? 'book-outline' : 'sparkles'} color={m.fallback ? C.sub : C.tabOn} />
            </View>
            <Text style={s.body} lineBreakStrategyIOS="hangul-word">{m.text}</Text>
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
          value={q} onChangeText={setQ} onSubmitEditing={() => ask(q)} returnKeyType="send" />
        <View style={{ width: 64 }}><Btn title="전송" color={C.navy} onPress={() => ask(q)} /></View>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}
