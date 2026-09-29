// FS-CHAT-001: AI 재난 대응 안내 — 공식 행동요령 검색 → 근거가 있을 때만 AI 답변, 없으면 기본 행동요령
import { useRef, useState } from 'react';
import { ActivityIndicator, ScrollView, Text, TextInput, View } from 'react-native';
import { Badge, Btn, C, Chip, Header, Icon, s } from '../../components/ui';
import { BASIC_GUIDE, GUIDES } from '../../data/mock';
import { useApp } from '../../store/AppContext';

type Msg = { id: number; me: boolean; text: string; sources?: string[]; fallback?: boolean };
const SUGGEST = ['지진이 나면 어떻게 해요?', '집 앞이 침수되고 있어요', '산불 연기가 보여요'];

// ponytail: 키워드 매칭으로 검색 흉내. 실제로는 서버에서 공식자료 검색 + AI 생성
function answer(q: string): Omit<Msg, 'id' | 'me'> {
  const hits = GUIDES.filter(g => g.keys.some(k => q.includes(k)));
  if (!hits.length) return { text: `질문과 관련된 공식 자료를 찾지 못했어요. 기본 행동요령을 안내합니다.\n\n${BASIC_GUIDE}`, fallback: true };
  return { text: hits.map(g => g.text).join('\n\n'), sources: hits.map(g => `${g.org} · ${g.title}`) };
}

export default function ChatScreen() {
  useApp(); // 테마 변경 시 다시 그려지도록 구독
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [q, setQ] = useState('');
  const [pending, setPending] = useState(false);
  const scroll = useRef<ScrollView>(null);

  const ask = (text: string) => {
    const t = text.trim();
    if (!t || pending) return;
    setMsgs(m => [...m, { id: Date.now(), me: true, text: t }]);
    setQ(''); setPending(true);
    setTimeout(() => { // AI 응답 흉내
      setMsgs(m => [...m, { id: Date.now(), me: false, ...answer(t) }]);
      setPending(false);
    }, 700);
  };

  return (
    <View style={s.fill}>
      <Header title="재난 행동요령 AI" />
      <ScrollView ref={scroll} onContentSizeChange={() => scroll.current?.scrollToEnd()} contentContainerStyle={{ padding: 16, gap: 12 }}>
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center', backgroundColor: C.primarySoft, padding: 12, borderRadius: 12 }}>
          <Icon name="information-circle" size={18} color={C.noticeText} />
          <Text style={{ color: C.noticeText, flex: 1, fontSize: 13 }}>행정안전부·소방청·기상청·산림청 공식 자료를 근거로 AI가 안내해요. 위급하면 119에 신고하세요.</Text>
        </View>
        {msgs.length === 0 && (
          <View style={[s.row, { flexWrap: 'wrap' }]}>{SUGGEST.map(x => <Chip key={x} text={x} on={false} onPress={() => ask(x)} />)}</View>
        )}
        {msgs.map(m => m.me ? (
          <View key={m.id} style={{ alignSelf: 'flex-end', maxWidth: '85%', backgroundColor: C.primary, borderRadius: 16, padding: 12 }}>
            <Text style={{ color: '#fff', fontSize: 15 }}>{m.text}</Text>
          </View>
        ) : (
          <View key={m.id} style={[s.card, { maxWidth: '92%', borderWidth: 1.5, borderStyle: 'dashed', borderColor: m.fallback ? C.border : C.primary }]}>
            <View style={{ alignSelf: 'flex-start', marginBottom: 6 }}>
              <Badge text={m.fallback ? '기본 행동요령' : 'AI 생성 안내'} icon={m.fallback ? 'book-outline' : 'sparkles'} color={m.fallback ? C.sub : C.primary} />
            </View>
            <Text style={s.body}>{m.text}</Text>
            {m.sources?.map(src => (
              <View key={src} style={[s.row, { marginTop: 6 }]}>
                <Icon name="document-text-outline" size={14} color={C.sub} />
                <Text style={[s.muted, { marginTop: 0 }]}>출처: {src}</Text>
              </View>
            ))}
          </View>
        ))}
        {pending && <View style={s.row}><ActivityIndicator color={C.primary} /><Text style={s.muted}>공식 자료를 찾고 있어요…</Text></View>}
      </ScrollView>
      <View style={[s.row, { padding: 12, backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.border }]}>
        <TextInput style={[s.input, { flex: 1, marginVertical: 0 }]} placeholder="재난 대응 방법을 물어보세요" placeholderTextColor={C.sub}
          value={q} onChangeText={setQ} onSubmitEditing={() => ask(q)} returnKeyType="send" />
        <View style={{ width: 64 }}><Btn title="전송" onPress={() => ask(q)} /></View>
      </View>
    </View>
  );
}
