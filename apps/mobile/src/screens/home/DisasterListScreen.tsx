// FS-DASH-002: 재난 목록 — 유형·상태 필터, 종료·해제 재난 별도 조회
// C안: 유형·상태 선택은 남색 띠 위 칩 두 줄, 재난은 승차권 한 장씩
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { DisasterCard } from '../../components/disaster';
import { BandTabs, Coupon } from '../../components/ticket';
import { Btn, Empty, Header, Page, useTheme } from '../../components/ui';
import { fetchDisasters, sameRegion, useLoad } from '../../data/server';
import { DisasterType, TYPES } from '../../data/types';
import { useApp } from '../../store/AppContext';

const TABS = ['전체', ...TYPES];
const STATES = ['발생·진행', '종료·해제'] as const;

export default function DisasterListScreen() {
  const { C, s } = useTheme();
  const { areas } = useApp();
  const [type, setType] = useState('전체');
  const [state, setState] = useState<(typeof STATES)[number]>('발생·진행');
  const ended = state === '종료·해제';
  const res = useLoad(`${type}|${state}`, () => fetchDisasters({ type: type === '전체' ? undefined : type as DisasterType, ended }));
  const list = res.data ?? [];
  return (
    <View style={s.fill}>
      <Header title="재난 목록" />
      {/* 띠는 칩 두 줄까지만: 재난 승차권은 띠 아래에 띄워 둠 (머리 띠가 상단 띠와 겹쳐 보이지 않게) */}
      <Page band={120}>
        <BandTabs label="재난 유형" items={TABS.map(t => ({ value: t, label: t }))} value={type} onChange={setType} />
        <BandTabs label="재난 상태" items={STATES.map(t => ({ value: t, label: t }))} value={state} onChange={setState} />
        {state === '종료·해제' && <Text style={s.muted}>종료·해제된 재난은 종료 시점부터 90일간 보관돼요.</Text>}
        {res.loading && <ActivityIndicator color={C.tabOn} accessibilityLabel="재난 정보를 불러오는 중" />}
        {/* 불러오지 못한 것을 '재난 없음'으로 보이지 않게 */}
        {res.error && <Coupon><Empty icon="cloud-offline-outline" text={`재난 정보를 불러오지 못했어요.\n${res.error}`} /><Btn title="다시 시도" icon="refresh" outline color={C.tabOn} onPress={res.reload} /></Coupon>}
        {res.data && list.length === 0 && <Coupon><Empty icon="search-outline" text="조건에 맞는 재난 정보가 없어요." /></Coupon>}
        {list.map(d => <DisasterCard key={d.id} d={d} starred={areas.some(a => sameRegion(d.region, a.region))} onPress={() => router.push(`/disasters/${d.id}`)} />)}
      </Page>
    </View>
  );
}
