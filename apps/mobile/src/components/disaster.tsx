// 재난 표시 공통 부품 (재난 목록·상세) — C안 '탑승권'
// 재난 한 건 = 승차권 한 장: 머리 띠 = 위험수준(진행 중 경보 빨강 · 주의보 노랑, 끝난 재난·정보·등급 없음은 남색)
// FS-DASH-002·003: 위험수준은 색 + 아이콘 모양 + 글자. 공식 등급이 없으면 만들지 않고 "공식 등급 없음"
// 끝난 재난은 지우지 않고 '종료·해제' 도장 (끝난 재난이 위험해 보이지 않게 띠는 남색)
import { Text, View } from 'react-native';
import { Disaster, LEVEL_ICON, TYPE_ICON, isActive } from '../data/mock';
import { Coupon, Field, Perf, Stamp } from './ticket';
import { Badge, Icon, useTheme } from './ui';

// 진행 중인 경보·주의보만 색 띠
export const signLevel = (d: Disaster) => (isActive(d) && (d.level === '경보' || d.level === '주의보') ? d.level : null);

// 최신 수집 실패 안내 (마지막 정상 데이터를 최신처럼 보이지 않게)
export function StaleNote({ d }: { d: Disaster }) {
  const { C, s } = useTheme();
  return d.stale ? (
    <View style={[s.row, { marginTop: 6 }]}>
      <Icon name="cloud-offline-outline" size={16} color={C.warning} />
      <Text style={[s.small, { flex: 1, color: C.warning, fontWeight: '700' }]}>최신 수집 실패 · 마지막 정상 갱신 {d.updatedAt}</Text>
    </View>
  ) : null;
}

// header: 상세 화면 맨 위 (요약 없이 등급·지역·시각만, 누르지 않음)
export function DisasterCard({ d, starred, onPress, header }: { d: Disaster; starred?: boolean; onPress?: () => void; header?: boolean }) {
  const { C, s } = useTheme();
  const ai = d.ai.status === '완료' && d.ai.text;
  const level = signLevel(d);
  const tone = level === '경보' ? 'red' : level === '주의보' ? 'yellow' : 'navy';
  const fg = tone === 'yellow' ? '#16181B' : '#FFFFFF';
  const ended = !isActive(d);
  return (
    <Coupon tone={tone} onPress={onPress} head={d.level ? `${d.type} ${d.level}` : `${d.type} · 공식 등급 없음`} headIcon={d.level ? LEVEL_ICON[d.level] : TYPE_ICON[d.type]}
      accessibilityLabel={`${d.region} ${d.type}${d.level ?? ' 공식 등급 없음'} ${d.status}${starred ? ', 관심지역' : ''}${d.stale ? ', 최신 수집 실패' : ''}`}
      right={<View style={s.row}>
        <Text style={[s.small, { color: fg, fontWeight: '800' }]}>{d.status}</Text>
        {onPress && <Icon name="chevron-forward" size={18} color={fg} />}
      </View>}>
      <View style={[s.row, { alignItems: 'flex-start' }]}>
        <Text style={[s.h2, { flex: 1, fontWeight: '900' }]}>{d.region}</Text>
        {starred && <Badge text="관심지역" icon="star" color={C.tabOn} />}
        {ended && <Stamp text={d.status} color={C.sub} />}
      </View>
      {!header && (
        <Text style={[s.body, { marginTop: 4 }]} numberOfLines={2} lineBreakStrategyIOS="hangul-word">
          {ai ? <Text style={{ color: C.tabOn, fontWeight: '700' }}>AI 요약 · </Text> : null}
          {ai || d.sources[0]?.text || '공식 원문을 불러오는 중이에요.'}
        </Text>
      )}
      <Perf />
      <View style={[s.row, { gap: 12 }]}>
        <Field label="발생" value={d.occurredAt} mono />
        <Field label="갱신" value={d.updatedAt} mono />
      </View>
      {/* AI 요약 옆에는 항상 원문 출처 */}
      <Text style={[s.small, { marginTop: 6 }]}>공식 원문 {d.sources.length}건 · {d.sources.map(x => x.org).join(', ')}</Text>
      <StaleNote d={d} />
    </Coupon>
  );
}
