// 재난 표시 공통 부품 (대시보드·목록·상세에서 함께 사용)
import { Text, View } from 'react-native';
import { Disaster, LEVEL_COLOR, LEVEL_ICON, TYPE_ICON, isActive } from '../data/mock';
import { Badge, C, Card, Icon, s } from './ui';

// FS-DASH-002·003: 위험수준은 색 + 아이콘 + 텍스트. 공식 등급이 없으면 만들지 않고 "등급 없음"
export const LevelBadge = ({ d }: { d: Disaster }) => d.level
  ? <Badge text={`${d.type} ${d.level}`} icon={LEVEL_ICON[d.level]} color={LEVEL_COLOR[d.level]} />
  : <Badge text={`${d.type} · 공식 등급 없음`} icon={TYPE_ICON[d.type]} color={C.sub} />;

export const StatusBadge = ({ d }: { d: Disaster }) =>
  <Badge text={d.status} color={isActive(d) ? C.danger : C.sub} />;

// 최신 수집 실패 안내 (마지막 정상 데이터를 최신처럼 보이지 않게)
export const StaleNote = ({ d }: { d: Disaster }) => d.stale ? (
  <View style={[s.row, { marginTop: 6 }]}>
    <Icon name="cloud-offline-outline" size={14} color={C.warning} />
    <Text style={[s.muted, { marginTop: 0, color: C.warning }]}>최신 수집 실패 · 마지막 정상 갱신 {d.updatedAt}</Text>
  </View>
) : null;

export function DisasterCard({ d, starred, onPress }: { d: Disaster; starred?: boolean; onPress: () => void }) {
  const ai = d.ai.status === '완료' && d.ai.text;
  return (
    <Card onPress={onPress}>
      <View style={[s.row, { flexWrap: 'wrap' }]}>
        <LevelBadge d={d} />
        {starred && <Badge text="관심지역" icon="star" color={C.primary} />}
        <View style={{ marginLeft: 'auto' }}><StatusBadge d={d} /></View>
      </View>
      <View style={[s.row, { marginTop: 8 }]}>
        <Text style={[s.bold, { flex: 1, fontSize: 16 }]}>{d.region}</Text>
        <Icon name="chevron-forward" size={18} color={C.sub} />
      </View>
      {/* AI 요약이 있으면 AI 표시와 함께, 없으면 공식 원문 */}
      <Text style={s.body} numberOfLines={2}>
        {ai ? <Text style={{ color: C.primary, fontWeight: '700' }}>AI 요약 · </Text> : null}
        {ai || d.sources[0].text}
      </Text>
      <Text style={s.muted}>발생 {d.occurredAt} · 갱신 {d.updatedAt} · {d.sources.map(x => x.org).join(', ')}</Text>
      <StaleNote d={d} />
    </Card>
  );
}

