// 재난 대비 체크리스트 + 완료율
import { useState } from 'react';
import { Text, View } from 'react-native';
import { C, Card, Chip, Icon, Page, s } from '../../components/ui';
import { CHECKLIST } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ChecklistScreen() {
  const { checked, setChecked } = useApp();
  const [type, setType] = useState('지진');
  const items = CHECKLIST[type];
  const key = (i: string) => `${type}:${i}`;
  const done = items.filter(i => checked[key(i)]).length;
  const pct = Math.round((done / items.length) * 100);
  return (
    <Page>
      <View style={s.row}>{Object.keys(CHECKLIST).map(t => <Chip key={t} text={t} on={type === t} onPress={() => setType(t)} />)}</View>
      <Card>
        <View style={s.row}>
          <Text style={[s.bold, { flex: 1 }]}>{type} 대비 완료율</Text>
          <Text style={[s.h2, { color: C.success }]}>{pct}%</Text>
        </View>
        <Text style={s.muted}>{done}/{items.length}개 완료</Text>
        <View style={s.bar}><View style={[s.barFill, { width: `${pct}%` }]} /></View>
      </Card>
      {items.map(i => {
        const on = !!checked[key(i)];
        return (
          <Card key={i} onPress={() => setChecked(c => ({ ...c, [key(i)]: !on }))}>
            <View style={[s.row, { gap: 12 }]}>
              <Icon name={on ? 'checkmark-circle' : 'ellipse-outline'} size={26} color={on ? C.success : C.sub} />
              <Text style={[s.body, on && { textDecorationLine: 'line-through', color: C.sub }]}>{i}</Text>
            </View>
          </Card>
        );
      })}
    </Page>
  );
}
