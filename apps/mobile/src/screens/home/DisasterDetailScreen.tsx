// FS-DASH-003 재난 상세, FS-AISUM-002 AI 요약 조회 — 공식 등급·원문 우선, AI 는 구분 표시
// C안 '탑승권': 재난 승차권(등급 색 띠) → 대피 안내 → AI 요약(점선, 공식 정보와 구분) → 공식 원문 쿠폰
import { router } from 'expo-router';
import { ActivityIndicator, Text, View } from 'react-native';
import { DisasterCard } from '../../components/disaster';
import { ShelterSign } from '../../components/shelter';
import { Coupon } from '../../components/ticket';
import { Badge, Btn, Header, Label, Page, useTheme } from '../../components/ui';
import { useApp } from '../../store/AppContext';
import { DISASTERS, Disaster, isActive } from '../../data/mock';

// 상태별 AI 요약 본문. 대기·실패 시 내용을 만들어 내지 않음
function AiBody({ ai }: { ai: Disaster['ai'] }) {
  const { C, s } = useTheme();
  switch (ai.status) {
    case '완료': return <Text style={s.body} lineBreakStrategyIOS="hangul-word">{ai.text}</Text>;
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
  const { C, s } = useTheme();
  const { location } = useApp();
  const d = DISASTERS.find(x => x.id === id);
  if (!d) return <View style={s.fill}><Header title="재난 정보" /><Text style={s.center}>재난 정보를 찾을 수 없습니다.</Text></View>;
  // 진행 중: 녹색 대피 안내판 + 행동요령. 끝난 재난: 아래쪽에 보조 버튼만
  const ask = <Btn title="행동요령 물어보기" icon="chatbubbles-outline" outline color={C.tabOn} onPress={() => router.push('/chat')} />;
  const actions = isActive(d) ? (
    <>
      <ShelterSign region={location} secondary={{ title: '대피소 목록', onPress: () => { if (router.canDismiss()) router.dismissAll(); router.navigate('/shelter'); } }} />
      {ask}
    </>
  ) : ask;
  return (
    <View style={s.fill}>
      <Header title={`${d.region} ${d.type}`} />
      <Page band={28}>
        {/* 상태 판 → (진행 중이면) 행동 → AI 요약 → 공식 원문 순 */}
        <DisasterCard d={d} header />
        {isActive(d) && actions}

        {/* AI 영역은 점선 테두리 + 'AI 생성' 표시로 공식 정보와 구분 */}
        <Coupon dashed>
          <View style={[s.row, { marginBottom: 6 }]}>
            <Label icon="sparkles" text="AI 요약" color={C.tabOn} />
            <View style={{ marginLeft: 'auto', marginBottom: 6 }}><Badge text={d.ai.status === '완료' ? 'AI 생성' : d.ai.status} color={C.tabOn} /></View>
          </View>
          <AiBody ai={d.ai} />
          <Text style={[s.muted, { marginTop: 8 }]}>AI 요약은 참고용이며 공식 등급·원문을 대체하지 않아요.{d.ai.at ? ` · 생성 ${d.ai.at}` : ''}</Text>
        </Coupon>

        {/* 발생·갱신 시각과 출처 기관은 맨 위 재난 승차권의 칸·출처 줄에 있음 */}
        {d.sources.map(src => (
          <Coupon key={src.org} head={`공식 원문 · ${src.org}`} headIcon="document-text">
            <Text style={s.body} selectable lineBreakStrategyIOS="hangul-word">{src.text}</Text>
          </Coupon>
        ))}
        {!isActive(d) && actions}
      </Page>
    </View>
  );
}
