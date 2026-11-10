// FS-DASH-002: 재난 목록 — 유형·상태 필터, 종료·해제 재난 별도 조회
// C안: 유형·상태 선택은 남색 띠 위 칩 두 줄, 재난은 승차권 한 장씩
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, View } from 'react-native';
import { DisasterCard } from '../../components/disaster';
import { BandTabs, Coupon } from '../../components/ticket';
import { Empty, Header, Page, useTheme } from '../../components/ui';
import { DISASTERS, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const TYPES = ['전체', '지진', '호우', '산불'];
const STATES = ['발생·진행', '종료·해제'] as const;

export default function DisasterListScreen() {
  const { s } = useTheme();
  const { areas } = useApp();
  const [type, setType] = useState('전체');
  const [state, setState] = useState<(typeof STATES)[number]>('발생·진행');
  const list = DISASTERS.filter(d => (type === '전체' || d.type === type) && isActive(d) === (state === '발생·진행'));
  return (
    <View style={s.fill}>
      <Header title="재난 목록" />
      {/* 띠는 칩 두 줄까지만: 재난 승차권은 띠 아래에 띄워 둠 (머리 띠가 상단 띠와 겹쳐 보이지 않게) */}
      <Page band={120}>
        <BandTabs label="재난 유형" items={TYPES.map(t => ({ value: t, label: t }))} value={type} onChange={setType} />
        <BandTabs label="재난 상태" items={STATES.map(t => ({ value: t, label: t }))} value={state} onChange={setState} />
        {state === '종료·해제' && <Text style={s.muted}>종료·해제된 재난은 종료 시점부터 90일간 보관돼요.</Text>}
        {list.length === 0 && <Coupon><Empty icon="search-outline" text="조건에 맞는 재난 정보가 없어요." /></Coupon>}
        {list.map(d => <DisasterCard key={d.id} d={d} starred={areas.includes(d.region)} onPress={() => router.push(`/disasters/${d.id}`)} />)}
      </Page>
    </View>
  );
}
