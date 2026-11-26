// FS-DASH-002: 재난 목록 — 유형·상태 필터, 종료·해제 재난 별도 조회
// C안: 유형·상태 선택은 남색 띠 위 칩 두 줄, 재난은 승차권 한 장씩
// FlatList: 보이는 카드만 그림 + 아래로 내리면 다음 묶음(20건)을 이어서 받음 (재난이 많아도 한 번에 전부 받지 않음)
import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Platform, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { DisasterCard } from '../../components/disaster';
import { BandTabs, Coupon } from '../../components/ticket';
import { Btn, Empty, Header, useLayout, useTheme } from '../../components/ui';
import { errText, fetchDisasterPage, sameRegion } from '../../data/server';
import { Disaster, DisasterType, TYPES } from '../../data/types';
import { useApp } from '../../store/AppContext';

const TABS = ['전체', ...TYPES];
const STATES = ['발생·진행', '종료·해제'] as const;
const BAND = 120;

// key = 이 결과를 받은 필터. run = 조회 세대 번호 (필터를 A→B→A 로 바꾸면 key 는 같아도 run 이 달라 이전 A 의 늦은 응답을 버림)
// page = 다음에 받을 묶음 번호
type Loaded = { key: string; run: number; list: Disaster[]; more: boolean; page: number; error?: string };
// 묶음 사이에 상태가 바뀌면(발생→진행중) 같은 재난이 두 번 올 수 있어 id 로 하나만 남김
const merge = (list: Disaster[], add: Disaster[]) => {
  const seen = new Set(list.map(d => d.id));
  return [...list, ...add.filter(d => !seen.has(d.id) && !!seen.add(d.id))];
};

export default function DisasterListScreen() {
  const { C, s } = useTheme();
  const { areas } = useApp();
  const { gutter, column } = useLayout();
  const { bottom } = useSafeAreaInsets();
  const [type, setType] = useState('전체');
  const [state, setState] = useState<(typeof STATES)[number]>('발생·진행');
  const [retry, setRetry] = useState(0);
  const [res, setRes] = useState<Loaded | null>(null);
  const [moreLoading, setMoreLoading] = useState(false);
  const ended = state === '종료·해제';
  const key = `${type}|${state}|${retry}`;
  const pageOf = (page: number) => fetchDisasterPage({ type: type === '전체' ? undefined : type as DisasterType, ended }, page);

  // 필터가 바뀌면 첫 묶음부터. 늦게 온 이전 필터의 응답은 버림
  const runs = useRef(0);
  useEffect(() => {
    let alive = true;
    const run = ++runs.current;
    pageOf(0).then(r => alive && setRes({ key, run, list: merge([], r.items), more: r.more, page: 1 }),
      e => alive && setRes({ key, run, list: [], more: false, page: 0, error: errText(e) }));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- key 가 type·state·retry 를 모두 담음
  }, [key]);

  const cur = res?.key === key ? res : null; // null = 첫 묶음을 받는 중
  // 다음 묶음 (실패했으면 그 묶음부터 다시). onEndReached 가 다시 그리기 전에 연달아 와도 한 번만 (ref 로 즉시 막음)
  const fetchingMore = useRef(false);
  const loadMore = (again = false) => {
    if (!cur || fetchingMore.current || (!again && (!cur.more || cur.error))) return;
    fetchingMore.current = true; setMoreLoading(true);
    const mine = (p: Loaded | null): p is Loaded => p?.run === cur.run && p.page === cur.page; // 같은 조회의 같은 묶음일 때만 반영
    pageOf(cur.page).then(r => setRes(p => (mine(p) ? { ...p, list: merge(p.list, r.items), more: r.more, page: p.page + 1, error: undefined } : p)),
      e => setRes(p => (mine(p) ? { ...p, error: errText(e) } : p)))
      .finally(() => { fetchingMore.current = false; setMoreLoading(false); });
  };

  return (
    <View style={s.fill}>
      <Header title="재난 목록" />
      <FlatList
        data={cur?.list ?? []}
        keyExtractor={d => d.id}
        renderItem={({ item: d }) => <DisasterCard d={d} starred={areas.some(a => sameRegion(d.region, a.region))} onPress={() => router.push(`/disasters/${d.id}`)} />}
        onEndReached={() => loadMore()}
        onEndReachedThreshold={0.5}
        keyboardShouldPersistTaps="handled"
        contentInsetAdjustmentBehavior="automatic"
        // Page 와 같은 모양: 위 끝에서 당겨지지 않게, 가운데 폭 칸 + 카드 사이 간격
        bounces={false} overScrollMode="never" style={Platform.OS === 'web' ? ({ overscrollBehaviorY: 'none' } as object) : undefined}
        contentContainerStyle={[column, { paddingTop: 16, paddingHorizontal: gutter, gap: 12, paddingBottom: 32 + (Platform.OS === 'ios' ? 0 : bottom) }]}
        ListHeaderComponent={
          <View style={{ gap: 12 }}>
            {/* 헤더와 이어지는 남색 띠 (칩 두 줄까지만, 재난 승차권은 띠 아래) */}
            {/* 이 View 는 목록 위 여백(16) 아래에서 시작 → 띠 끝을 Page 와 같은 위치(목록 맨 위 + BAND)에 맞추려고 16 을 뺌 */}
            <View style={{ position: 'absolute', left: -1000, right: -1000, top: -1016, height: 1000 + BAND, backgroundColor: C.head }} />
            <BandTabs label="재난 유형" items={TABS.map(t => ({ value: t, label: t }))} value={type} onChange={setType} />
            <BandTabs label="재난 상태" items={STATES.map(t => ({ value: t, label: t }))} value={state} onChange={setState} />
            {ended && <Text style={s.muted}>종료·해제된 재난은 종료 시점부터 90일간 보관돼요.</Text>}
            {!cur && <ActivityIndicator color={C.tabOn} accessibilityLabel="재난 정보를 불러오는 중" />}
            {cur?.list.length === 0 && !cur.error && <Coupon><Empty icon="search-outline" text="조건에 맞는 재난 정보가 없어요." /></Coupon>}
          </View>
        }
        ListFooterComponent={
          <View style={{ gap: 12 }}>
            {moreLoading && <ActivityIndicator color={C.tabOn} accessibilityLabel="재난 정보를 더 불러오는 중" />}
            {/* 불러오지 못한 것을 '재난 없음'으로 보이지 않게 */}
            {!!cur?.error && <Coupon><Empty icon="cloud-offline-outline" text={`재난 정보를 불러오지 못했어요.\n${cur.error}`} />
              <Btn title="다시 시도" icon="refresh" outline color={C.tabOn} onPress={() => (cur.list.length ? loadMore(true) : setRetry(n => n + 1))} /></Coupon>}
          </View>
        }
      />
    </View>
  );
}
