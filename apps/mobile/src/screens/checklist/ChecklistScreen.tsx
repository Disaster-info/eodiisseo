// 재난 행동 체크리스트 — 완료 막대 → 공식 행동요령 항목 (서버의 체크리스트 템플릿, 완료 여부는 회원별로 서버에 저장)
// 재난 유형은 남색 띠 위 칩. 처음에는 내 기준 위치에서 진행 중인 가장 위험한 재난의 유형을 보여 줌 (없으면 호우)
import { useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { BandTabs, Coupon, MONO, Perf } from '../../components/ticket';
import { Btn, Empty, Icon, Page, notify, useTheme } from '../../components/ui';
import { Checklist, checkItem, errText, fetchChecklist, fetchDisasters, sameRegion, useLoad } from '../../data/server';
import { DisasterType, TYPES, isActive } from '../../data/types';
import { useApp } from '../../store/AppContext';

const RANK = { 경보: 0, 주의보: 1, 정보: 2 } as const;

export default function ChecklistScreen() {
  const { C, s } = useTheme();
  const { location, locState } = useApp();
  // 지금 내 기준 위치에서 진행 중인 재난 (위험한 순 첫 번째)
  const disasters = useLoad('active', () => fetchDisasters());
  const now = locState === 'ok' ? (disasters.data ?? []).filter(d => isActive(d) && sameRegion(d.region, location))
    .sort((a, b) => (a.level ? RANK[a.level] : 3) - (b.level ? RANK[b.level] : 3))[0] : undefined;
  const [chosen, setChosen] = useState<DisasterType | null>(null);
  const type = chosen ?? now?.type ?? '호우';
  const list = useLoad(type, () => fetchChecklist(type));
  // 누른 항목은 바로 화면에 반영하고 서버 결과로 확정. 실패하면 되돌리고 안내
  const [local, setLocal] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const items = (list.data?.items ?? []).map(i => ({ ...i, done: local[i.id] ?? i.done }));
  const done = items.filter(i => i.done).length;
  const pct = items.length ? Math.round((done / items.length) * 100) : 0;
  const toggle = (c: Checklist, id: string, on: boolean) => {
    if (busy[id]) return;
    setLocal(l => ({ ...l, [id]: on })); setBusy(b => ({ ...b, [id]: true }));
    checkItem(c.id, id, on)
      .catch(e => { setLocal(l => ({ ...l, [id]: !on })); notify('저장하지 못했어요', errText(e)); })
      .finally(() => setBusy(b => ({ ...b, [id]: false })));
  };
  const tabs = TYPES.map(t => ({ value: t, label: t === now?.type ? `${t} · 진행 중` : t }));

  return (
    <Page band={64}>
      <BandTabs label="재난 유형" items={tabs} value={type} onChange={t => { setChosen(t); setLocal({}); }} />
      {list.loading && <ActivityIndicator color={C.tabOn} accessibilityLabel="체크리스트를 불러오는 중" />}
      {list.error && (
        <Coupon><Empty icon="cloud-offline-outline" text={`체크리스트를 불러오지 못했어요.\n${list.error}`} />
          <Btn title="다시 시도" icon="refresh" outline color={C.tabOn} onPress={list.reload} /></Coupon>
      )}
      {list.data && (
        <>
          <Coupon>
            <Text style={s.small}>{list.data.org ?? '공식'} {list.data.title ?? `${type} 행동요령`}{type === now?.type ? ` · 지금 ${now.region} ${now.type}${now.level ?? ''}` : ''}</Text>
            <View style={[s.row, { gap: 12, marginTop: 4 }]} accessible accessibilityLabel={`완료 ${items.length}개 중 ${done}개, ${pct}퍼센트`}>
              <Text style={s.bold}>완료 <Text style={{ fontFamily: MONO, color: C.carbon }}>{done}/{items.length}</Text></Text>
              <View style={[s.bar, { flex: 1, marginTop: 0, borderRadius: 2 }]}><View style={[s.barFill, { width: `${pct}%`, borderRadius: 2, backgroundColor: C.navy }]} /></View>
            </View>
          </Coupon>
          <Coupon head={`${type} 행동 점검`} tone="navy" right={`${done}/${items.length}`}>
            {items.length === 0 && <Text style={s.muted}>등록된 항목이 없어요.</Text>}
            {items.map((i, ii) => (
              <View key={i.id}>
                {ii > 0 && <Perf />}
                <Pressable onPress={() => toggle(list.data!, i.id, !i.done)} accessibilityRole="checkbox" accessibilityState={{ checked: i.done, busy: !!busy[i.id] }} accessibilityLabel={i.text}
                  style={({ pressed }) => [s.row, { gap: 12, minHeight: 48 }, pressed && s.pressed]}>
                  <View style={{ width: 26, height: 26, borderRadius: 5, borderWidth: 2, borderColor: i.done ? C.navy : C.sub, backgroundColor: i.done ? C.navy : 'transparent',
                    justifyContent: 'center', alignItems: 'center', transform: [{ rotate: i.done ? '-4deg' : '0deg' }] }}>
                    {i.done && <Icon name="checkmark" size={18} color="#FFFFFF" />}
                  </View>
                  <Text style={[s.body, { flex: 1 }, i.done && { textDecorationLine: 'line-through', color: C.sub }]}>{i.text}</Text>
                </Pressable>
              </View>
            ))}
          </Coupon>
        </>
      )}
    </Page>
  );
}
