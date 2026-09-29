// FS-DASH-003 재난 상세, FS-AISUM-002 AI 요약 조회 — 공식 등급·원문 우선, AI 는 구분 표시
import { router } from 'expo-router';
import { ActivityIndicator, Text, View } from 'react-native';
import { LevelBadge, StaleNote, StatusBadge } from '../../components/disaster';
import { Badge, Btn, C, Card, Header, InfoRow, Label, Page, s } from '../../components/ui';
import { DISASTERS, Disaster } from '../../data/mock';
import { useApp } from '../../store/AppContext';

// 상태별 AI 요약 본문. 대기·실패 시 내용을 만들어 내지 않음
function AiBody({ ai }: { ai: Disaster['ai'] }) {
  switch (ai.status) {
    case '완료': return <Text style={s.body}>{ai.text}</Text>;
    case '대기': return <View style={s.row}><ActivityIndicator color={C.primary} /><Text style={s.muted}>요약을 생성하고 있어요. 아래 공식 원문을 확인하세요.</Text></View>;
    case '실패': return <Text style={s.muted}>요약을 만들지 못했어요. 아래 공식 원문을 확인하세요.</Text>;
    case '갱신 대기': return (
      <>
        <Text style={[s.muted, { color: C.warning, marginBottom: 6 }]}>원문이 변경되어 새 요약을 생성 중이에요. 아래는 이전 원문 기준 요약입니다.</Text>
        <Text style={[s.body, { color: C.sub }]}>{ai.text}</Text>
      </>
    );
  }
}

export default function DisasterDetailScreen({ id }: { id: number }) {
  useApp(); // 테마 변경 시 다시 그려지도록 구독
  const d = DISASTERS.find(x => x.id === id);
  if (!d) return <View style={s.fill}><Header title="재난 정보" /><Text style={s.center}>재난 정보를 찾을 수 없습니다.</Text></View>;
  return (
    <View style={s.fill}>
      <Header title={`${d.region} ${d.type}`} />
      <Page>
        <View style={s.row}><LevelBadge d={d} /><StatusBadge d={d} /></View>
        <StaleNote d={d} />

        {/* AI 영역은 점선 테두리 + 'AI 생성' 표시로 공식 정보와 구분 */}
        <View style={[s.card, { borderWidth: 1.5, borderStyle: 'dashed', borderColor: C.primary }]}>
          <View style={[s.row, { marginBottom: 6 }]}>
            <Label icon="sparkles" text="AI 요약" />
            <View style={{ marginLeft: 'auto', marginBottom: 6 }}><Badge text={d.ai.status === '완료' ? 'AI 생성' : d.ai.status} color={C.primary} /></View>
          </View>
          <AiBody ai={d.ai} />
          <Text style={[s.muted, { marginTop: 8 }]}>AI 요약은 참고용이며 공식 등급·원문을 대체하지 않아요.{d.ai.at ? ` · 생성 ${d.ai.at}` : ''}</Text>
        </View>

        {d.sources.map(src => (
          <Card key={src.org}>
            <Label icon="document-text" text={`공식 원문 · ${src.org}`} />
            <Text style={s.body} selectable>{src.text}</Text>
          </Card>
        ))}
        <Card>
          <InfoRow icon="time-outline" text={`발생 시각 ${d.occurredAt}`} />
          <InfoRow icon="refresh-outline" text={`마지막 갱신 ${d.updatedAt}`} />
          <InfoRow icon="business-outline" text={`출처 ${d.sources.map(x => x.org).join(', ')}`} />
        </Card>
        <Btn title="주변 대피소 보기" icon="shield" onPress={() => { router.dismissAll(); router.navigate('/shelter'); }} />
        <Btn title="행동요령 물어보기" icon="chatbubbles-outline" outline onPress={() => router.push('/chat')} />
      </Page>
    </View>
  );
}
