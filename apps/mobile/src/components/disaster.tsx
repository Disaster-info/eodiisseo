// 재난 표시 공통 부품 (대시보드·목록·상세에서 함께 사용)
import { Text, View } from 'react-native';
import { Disaster, LEVEL_ICON, TYPE_ICON, isActive } from '../data/mock';
import { Badge, Card, Icon, Sign, levelFill, levelInk, signInk, useTheme } from './ui';

// FS-DASH-002·003: 위험수준은 표지판처럼 색 + 아이콘 모양 + 텍스트. 공식 등급이 없으면 만들지 않고 "등급 없음"
export function LevelBadge({ d }: { d: Disaster }) {
  const { C } = useTheme();
  return d.level
    ? <Badge solid text={`${d.type} ${d.level}`} icon={LEVEL_ICON[d.level]} color={levelFill(C, d.level)} fg={levelInk(C, d.level)} />
    : <Badge text={`${d.type} · 공식 등급 없음`} icon={TYPE_ICON[d.type]} color={C.sub} />;
}

// 상태는 색 대신 글자로 (빨강은 경보 전용). ink: 표지판 위에 놓일 때의 글자색
export function StatusBadge({ d, ink }: { d: Disaster; ink?: string }) {
  const { C } = useTheme();
  return <Badge text={d.status} color={ink ?? (isActive(d) ? C.text : C.sub)} />;
}

// 최신 수집 실패 안내 (마지막 정상 데이터를 최신처럼 보이지 않게)
export function StaleNote({ d, ink }: { d: Disaster; ink?: string }) {
  const { C, s } = useTheme();
  const color = ink ?? C.warning;
  return d.stale ? (
    <View style={[s.row, { marginTop: 6 }]}>
      <Icon name="cloud-offline-outline" size={16} color={color} />
      <Text style={[s.small, { flex: 1, color, fontWeight: ink ? '700' : '400' }]}>최신 수집 실패 · 마지막 정상 갱신 {d.updatedAt}</Text>
    </View>
  ) : null;
}

// 진행 중인 경보·주의보만 표지판으로. 끝난 재난·정보·등급 없음은 중립 카드 (끝난 재난이 위험해 보이지 않게)
export const signLevel = (d: Disaster) => (isActive(d) && (d.level === '경보' || d.level === '주의보') ? d.level : null);

// header: 상세 화면 맨 위 (요약·메타 없이 등급·지역·상태만, 누르지 않음)
export function DisasterCard({ d, starred, onPress, header }: { d: Disaster; starred?: boolean; onPress?: () => void; header?: boolean }) {
  const { C, s } = useTheme();
  const ai = d.ai.status === '완료' && d.ai.text;
  const meta = `발생 ${d.occurredAt} · 갱신 ${d.updatedAt}`;
  const orgs = `공식 원문 ${d.sources.length}건 · ${d.sources.map(x => x.org).join(', ')}`; // AI 요약 옆에는 항상 원문 출처
  const level = signLevel(d);

  if (!level) return (
    <Card onPress={onPress}>
      <View style={[s.row, { flexWrap: 'wrap' }]}>
        <LevelBadge d={d} />
        {starred && <Badge text="관심지역" icon="star" color={C.primary} />}
        <View style={{ marginLeft: 'auto' }}><StatusBadge d={d} /></View>
      </View>
      <View style={[s.row, { marginTop: 8 }]}>
        <Text style={[s.bold, { flex: 1 }]}>{d.region}</Text>
        {onPress && <Icon name="chevron-forward" size={18} color={C.sub} />}
      </View>
      {!header && (
        <>
          <Text style={s.body} numberOfLines={2} lineBreakStrategyIOS="hangul-word">
            {ai ? <Text style={{ color: C.primary, fontWeight: '700' }}>AI 요약 · </Text> : null}
            {ai || d.sources[0].text}
          </Text>
          <Text style={s.muted}>{meta}</Text>
          <Text style={s.muted}>{orgs}</Text>
        </>
      )}
      <StaleNote d={d} />
    </Card>
  );

  // 크기 램프: 경보 판은 등급명을 크게, 주의보 판은 한 단계 작게
  // 판 위 글자는 한 가지 색(흰색 / 검정)만 쓰고 위계는 크기·굵기로 (옅은 보조색은 대비 미달)
  const big = level === '경보';
  const tone = big ? 'red' : 'yellow';
  const ink = signInk(C, tone);
  return (
    <Sign tone={tone} onPress={onPress} label={`${d.region} ${d.type}${level} ${d.status}`}>
      <View style={s.row}>
        <Icon name={LEVEL_ICON[level]} size={big ? 30 : 22} color={ink} />
        <Text style={[big ? s.h1 : s.h2, { color: ink, flex: 1, fontWeight: '900' }]}>{d.type}{level}</Text>
        <StatusBadge d={d} ink={ink} />
      </View>
      <View style={s.row}>
        <Text style={[s.bold, { color: ink, flex: 1 }]}>{d.region}{starred ? ' · 관심지역' : ''}</Text>
        {onPress && <Icon name="chevron-forward" size={18} color={ink} />}
      </View>
      {!header && (
        <>
          <Text style={[s.body, { color: ink }]} numberOfLines={big ? 2 : 1} lineBreakStrategyIOS="hangul-word">
            {ai ? <Text style={{ fontWeight: '700' }}>AI 요약 · </Text> : null}
            {ai || d.sources[0].text}
          </Text>
          <Text style={[s.small, { color: ink }]}>{meta}</Text>
          <Text style={[s.small, { color: ink, fontWeight: '700' }]}>{orgs}</Text>
        </>
      )}
      <StaleNote d={d} ink={ink} />
    </Sign>
  );
}
