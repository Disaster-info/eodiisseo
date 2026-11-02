// 재난 대비 체크리스트 + 완료율
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Card, Icon, Page, useTheme, Segmented } from '../../components/ui';
import { CHECKLIST } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ChecklistScreen() {
  const { C, s } = useTheme();
  const { checked, setChecked } = useApp();
  const [type, setType] = useState('지진');
  const items = CHECKLIST[type];
  const key = (i: string) => `${type}:${i}`;
  const done = items.filter(i => checked[key(i)]).length;
  const pct = Math.round((done / items.length) * 100);
  return (
    <Page>
      <Segmented label="재난 유형" items={Object.keys(CHECKLIST)} value={type} onChange={setType} />
      <Card>
        <View style={s.row}>
          <Text style={[s.bold, { flex: 1 }]}>{type} 대비 완료율</Text>
          <Text style={[s.h2, { color: C.success }]}>{pct}%</Text>
        </View>
        <Text style={s.muted}>{done}/{items.length}개 완료</Text>
        <View style={s.bar}><View style={[s.barFill, { width: `${pct}%` }]} /></View>
      </Card>
      <View style={[s.card, { paddingVertical: 4 }]}>
        {items.map((i, n) => {
          const on = !!checked[key(i)];
          return (
            <Pressable key={i} onPress={() => setChecked(c => ({ ...c, [key(i)]: !on }))} accessibilityRole="checkbox" accessibilityState={{ checked: on }} accessibilityLabel={i}
              style={({ pressed }) => [s.row, { gap: 12, minHeight: 56 }, n > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.border }, pressed && s.pressed]}>
              <Icon name={on ? 'checkmark-circle' : 'ellipse-outline'} size={26} color={on ? C.success : C.sub} />
              <Text style={[s.body, { flex: 1 }, on && { textDecorationLine: 'line-through', color: C.sub }]}>{i}</Text>
            </Pressable>
          );
        })}
      </View>
    </Page>
  );
}
