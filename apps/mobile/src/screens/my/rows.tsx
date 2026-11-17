// 내정보·관리자 설정 화면의 설정 목록 모양 (한 줄 Row, 묶음 Group)
import { Children, ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { Icon, IconName, useTheme } from '../../components/ui';

// 설정 목록의 한 줄: 아이콘 + 제목 + (부가설명) + 오른쪽(값·스위치 등)
// value: 현재 값 (오른쪽 회색 글자 + 화살표). danger: 탈퇴처럼 되돌릴 수 없는 동작 (빨간 아이콘·제목)
export function Row({ icon, title, sub, value, right, onPress, danger }:
  { icon: IconName; title: string; sub?: string; value?: string; right?: ReactNode; onPress?: () => void; danger?: boolean }) {
  const { C, s } = useTheme();
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={value ? `${title}, ${value}` : undefined}
      style={({ pressed }) => [s.row, { gap: 12, paddingVertical: 8, minHeight: 52 }, pressed && s.pressed]}>
      <Icon name={icon} size={22} color={danger ? C.danger : C.sub} />
      <View style={{ flex: 1 }}>
        <Text style={[s.body, danger && { color: C.danger, fontWeight: '700' }]}>{title}</Text>
        {!!sub && <Text style={[s.small, { marginTop: 1 }]}>{sub}</Text>}
      </View>
      {!!value && <Text style={[s.muted, { marginTop: 0, flexShrink: 1, textAlign: 'right' }]} numberOfLines={1}>{value}</Text>}
      {right ?? (onPress && <Icon name="chevron-forward" size={18} color={C.sub} />)}
    </Pressable>
  );
}

// 묶음: 제목(선택) + 카드 안 행 사이에 얇은 구분선
export function Group({ title, children }: { title?: string; children: ReactNode }) {
  const { s } = useTheme();
  const rows = Children.toArray(children).filter(Boolean);
  return (
    <View>
      {!!title && <Text style={[s.small, { fontWeight: '700', marginLeft: 16, marginBottom: 6 }]}>{title}</Text>}
      <View style={[s.card, { paddingVertical: 2 }]}>
        {rows.map((r, i) => <View key={i} style={i > 0 && s.divTop}>{r}</View>)}
      </View>
    </View>
  );
}
