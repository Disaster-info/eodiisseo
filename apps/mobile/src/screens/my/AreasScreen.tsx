// FS-AREA-001~004: 관심지역 등록·조회·변경(지역·표시명)·삭제 (최대 5개, 중복 불가 — 서버가 최종 판단)
// 지역은 장소 검색(TMAP)으로 찾고, 고른 곳의 시·군·구 코드로 서버에 등록
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Text, TextInput, View } from 'react-native';
import { ActionMenu, Badge, Btn, Card, Empty, Header, Icon, Page, confirm, notify, useTheme } from '../../components/ui';
import { areaApi, errText } from '../../data/server';
import { Poi, regionInfoOf, searchPois } from '../../data/tmap';
import { Area } from '../../data/types';
import { useApp } from '../../store/AppContext';

const MAX_AREAS = 5;

export default function AreasScreen() {
  const { C, s } = useTheme();
  const { areas, reloadAreas, rememberRegion, noti, setNoti } = useApp();
  const [editing, setEditing] = useState<Area | null>(null); // 지역을 변경 중인 기존 관심지역
  const [naming, setNaming] = useState<{ area: Area; text: string } | null>(null); // 표시명 수정 중
  const [busy, setBusy] = useState(false);
  const [q, setQ] = useState('');
  const [pois, setPois] = useState<Poi[] | null | 'loading' | 'error'>(null);
  const searchId = useRef(0); // 늦게 온 이전 검색 결과는 버림
  useEffect(() => () => { searchId.current++; }, []); // 화면을 떠나면 진행 중인 검색 결과는 버림

  const run = async (title: string, work: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    try { await work(); } catch (e) { notify(title, errText(e)); } finally { setBusy(false); reloadAreas().catch(() => {}); }
  };
  const search = () => {
    if (!q.trim()) return;
    const my = ++searchId.current;
    setPois('loading');
    searchPois(q.trim()).then(r => my === searchId.current && setPois(r), () => my === searchId.current && setPois('error'));
  };
  // 고른 장소 → 시·군·구 코드 → 등록 또는 변경. 새로 등록한 지역은 알림 대상에도 넣음 (알림이 조용히 빠지지 않게)
  const pick = (p: Poi) => run(editing ? '지역을 바꾸지 못했어요' : '관심지역을 등록하지 못했어요', async () => {
    const { region, code } = await regionInfoOf(p);
    if (!code) throw new Error('이 장소의 시·군·구 코드를 확인하지 못했어요. 다른 장소로 찾아보세요.');
    rememberRegion(code, region); // 나중에 별칭을 붙여도 이 지역의 재난을 찾을 수 있게
    if (areas.some(a => a.code === code && a.id !== editing?.id)) throw new Error('이미 등록된 관심지역이에요.');
    if (editing) {
      // 표시 이름이 확인된 지역 이름 그대로였을 때만 새 지역 이름으로 (예전 지역 이름이 남아 다른 곳으로 오해하지 않게)
      // 별칭이거나, 다른 기기에서 등록해 별칭인지 알 수 없으면 기존 이름을 지킴
      await areaApi.move(editing.id, code, editing.regionKnown && editing.name === editing.region ? region : editing.name);
      setEditing(null);
    } else {
      if (areas.length >= MAX_AREAS) throw new Error(`관심지역은 최대 ${MAX_AREAS}개까지 등록할 수 있어요.`);
      const a = await areaApi.create(code, region);
      await setNoti({ areaIds: [...noti.areaIds.filter(x => x !== a.id), a.id] }).catch(() => {});
    }
    setQ(''); setPois(null);
  });
  const remove = (a: Area) => run('삭제하지 못했어요', async () => {
    if (editing?.id === a.id) setEditing(null);
    await areaApi.remove(a.id);
  });
  const saveName = () => {
    if (!naming) return;
    const text = naming.text.trim();
    if (!text) return notify('표시 이름을 입력하세요');
    run('이름을 바꾸지 못했어요', async () => { await areaApi.rename(naming.area.id, text); setNaming(null); });
  };
  return (
    <View style={s.fill}>
      <Header title={`관심지역 (${areas.length}/${MAX_AREAS})`} />
      <Page>
        {areas.length === 0 && <Empty icon="star-outline" text={'등록된 관심지역이 없어요.\n아래에서 지역을 찾아 추가하세요. 등록하지 않아도 앱은 그대로 쓸 수 있어요.'} />}
        {areas.map(a => (
          <Card key={a.id}>
            <View style={s.row}>
              <Icon name="star" size={18} color={C.warning} />
              <View style={{ flex: 1 }}>
                <Text style={s.bold}>{a.name}</Text>
                {a.region !== a.name && <Text style={[s.muted, { marginTop: 0 }]}>{a.region}</Text>}
              </View>
              {editing?.id === a.id && <Badge text="변경 중" color={C.primary} />}
              <ActionMenu title={a.name} items={[
                { text: '표시 이름 바꾸기', onPress: () => setNaming({ area: a, text: a.name }) },
                { text: '다른 지역으로 변경', onPress: () => setEditing(a) },
                { text: '관심지역 삭제', destructive: true, onPress: () => confirm('관심지역 삭제', `${a.name}을(를) 관심지역에서 삭제할까요?\n이 지역 알림도 함께 꺼져요.`, '삭제', () => remove(a)) },
              ]} />
            </View>
            {naming?.area.id === a.id && (
              <>
                <TextInput accessibilityLabel="관심지역 표시 이름" style={s.input} value={naming.text} onChangeText={text => setNaming({ area: a, text })} maxLength={50}
                  placeholder="예: 우리집, 회사" placeholderTextColor={C.sub} autoFocus onSubmitEditing={saveName} />
                <View style={s.row}>
                  <View style={{ flex: 1 }}><Btn title="취소" outline onPress={() => setNaming(null)} /></View>
                  <View style={{ flex: 1 }}><Btn title="저장" onPress={saveName} /></View>
                </View>
              </>
            )}
          </Card>
        ))}
        <Text style={s.section}>{editing ? `'${editing.name}' 을(를) 변경할 지역 찾기` : '지역 추가'}</Text>
        {editing && <Btn title="지역 변경 취소" outline onPress={() => setEditing(null)} />}
        <TextInput accessibilityLabel="지역·장소 검색" style={[s.input, { backgroundColor: C.card }]} value={q}
          onChangeText={v => { setQ(v); setPois(null); searchId.current++; }} onSubmitEditing={search} returnKeyType="search"
          placeholder="지역·장소 검색 후 선택 (예: 강남구청, 수원역)" placeholderTextColor={C.sub} />
        <Btn title="검색" icon="search" outline onPress={search} />
        {(busy || pois === 'loading') && <ActivityIndicator color={C.primary} accessibilityLabel="처리 중" />}
        {pois === 'error' && <Text style={s.notice}>장소 검색에 실패했어요. 잠시 후 다시 시도해 주세요.</Text>}
        {Array.isArray(pois) && pois.length === 0 && <Empty icon="search-outline" text="검색된 장소가 없어요." />}
        {Array.isArray(pois) && pois.map(p => (
          <Card key={p.id} onPress={() => pick(p)}>
            <View style={s.row}>
              <Icon name="location-outline" size={22} color={C.sub} />
              <View style={{ flex: 1 }}>
                <Text style={s.body}>{p.region}</Text>
                <Text style={s.muted}>{p.name} · {p.addr}</Text>
              </View>
            </View>
          </Card>
        ))}
      </Page>
    </View>
  );
}
