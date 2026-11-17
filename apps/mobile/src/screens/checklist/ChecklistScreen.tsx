// 재난 행동 체크리스트 — C안 시안 구성: 완료 막대 → 시간 순서 칸('지금 바로'는 빨간 띠, '나서기 전'·'이동할 때'는 남색 띠) → 모두 지우기
// 재난 유형은 남색 띠 위 칩. 처음에는 내 기준 위치에서 진행 중인 가장 위험한 재난의 유형을 보여 줌 (없으면 호우)
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { BandTabs, Coupon, MONO, Perf } from '../../components/ticket';
import { Icon, Page, confirm, useTheme } from '../../components/ui';
import { CHECKLIST, DISASTERS, Disaster, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

type Type = Disaster['type'];
const RANK = { 경보: 0, 주의보: 1, 정보: 2 } as const;
const ORG: Record<Type, string> = { 호우: '행정안전부', 지진: '행정안전부', 산불: '산림청' };

export default function ChecklistScreen() {
  const { C, s } = useTheme();
  const { checked, setChecked, location } = useApp();
  // 지금 내 기준 위치에서 진행 중인 재난 (위험한 순 첫 번째)
  const now = DISASTERS.filter(d => isActive(d) && d.region === location).sort((a, b) => (a.level ? RANK[a.level] : 3) - (b.level ? RANK[b.level] : 3))[0];
  const [type, setType] = useState<Type>(now?.type ?? '호우');
  const groups = CHECKLIST[type];
  const key = (i: string) => `${type}:${i}`;
  const all = groups.flatMap(g => g.items);
  const done = all.filter(i => checked[key(i)]).length;
  const pct = Math.round((done / all.length) * 100);
  const reset = () => confirm('체크 모두 지우기', `${type} 체크리스트의 체크를 모두 지울까요?`, '지우기',
    () => setChecked(c => Object.fromEntries(Object.entries(c).filter(([k]) => !k.startsWith(`${type}:`)))));
  const tabs = (Object.keys(CHECKLIST) as Type[]).map(t => ({ value: t, label: t === now?.type ? `${t} · 진행 중` : t }));

  return (
    <Page band={64}>
      <BandTabs label="재난 유형" items={tabs} value={type} onChange={setType} />
      <Coupon>
        <Text style={s.small}>{ORG[type]} {type} 행동요령{type === now?.type ? ` · 지금 ${now.region} ${now.type}${now.level ?? ''}` : ''}</Text>
        <View style={[s.row, { gap: 12, marginTop: 4 }]} accessible accessibilityLabel={`완료 ${all.length}개 중 ${done}개, ${pct}퍼센트`}>
          <Text style={s.bold}>완료 <Text style={{ fontFamily: MONO, color: C.carbon }}>{done}/{all.length}</Text></Text>
          <View style={[s.bar, { flex: 1, marginTop: 0, borderRadius: 2 }]}><View style={[s.barFill, { width: `${pct}%`, borderRadius: 2, backgroundColor: C.navy }]} /></View>
        </View>
      </Coupon>
      {groups.map((g, gi) => {
        const n = g.items.filter(i => checked[key(i)]).length;
        return (
          <Coupon key={g.when} head={g.when} tone={gi === 0 ? 'red' : 'navy'} right={`${n}/${g.items.length}`}>
            {g.items.map((i, ii) => {
              const on = !!checked[key(i)];
              return (
                <View key={i}>
                  {ii > 0 && <Perf />}
                  <Pressable onPress={() => setChecked(c => ({ ...c, [key(i)]: !on }))} accessibilityRole="checkbox" accessibilityState={{ checked: on }} accessibilityLabel={`${g.when}: ${i}`}
                    style={({ pressed }) => [s.row, { gap: 12, minHeight: 48 }, pressed && s.pressed]}>
                    <View style={{ width: 26, height: 26, borderRadius: 5, borderWidth: 2, borderColor: on ? C.navy : C.sub, backgroundColor: on ? C.navy : 'transparent',
                      justifyContent: 'center', alignItems: 'center', transform: [{ rotate: on ? '-4deg' : '0deg' }] }}>
                      {on && <Icon name="checkmark" size={18} color="#FFFFFF" />}
                    </View>
                    <Text style={[s.body, { flex: 1 }, on && { textDecorationLine: 'line-through', color: C.sub }]}>{i}</Text>
                  </Pressable>
                </View>
              );
            })}
          </Coupon>
        );
      })}
      {done > 0 && (
        <Pressable onPress={reset} accessibilityRole="button" style={({ pressed }) => [{ alignSelf: 'center', minHeight: 44, justifyContent: 'center', paddingHorizontal: 12 }, pressed && s.pressed]}>
          <Text style={[s.muted, { textDecorationLine: 'underline' }]}>체크 모두 지우기</Text>
        </Pressable>
      )}
    </Page>
  );
}
