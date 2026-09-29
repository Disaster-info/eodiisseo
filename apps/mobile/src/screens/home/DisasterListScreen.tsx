// FS-DASH-002: 재난 목록 — 유형·상태 필터, 종료·해제 재난 별도 조회
import { router } from 'expo-router';
import { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { DisasterCard } from '../../components/disaster';
import { Chip, Empty, Header, Page, s } from '../../components/ui';
import { DISASTERS, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const TYPES = ['전체', '지진', '호우', '산불'];
const STATES = ['발생·진행', '종료·해제'] as const;

export default function DisasterListScreen() {
  const { areas } = useApp();
  const [type, setType] = useState('전체');
  const [state, setState] = useState<(typeof STATES)[number]>('발생·진행');
  const list = DISASTERS.filter(d => (type === '전체' || d.type === type) && isActive(d) === (state === '발생·진행'));
  return (
    <View style={s.fill}>
      <Header title="재난 목록" />
      <Page>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
          {TYPES.map(t => <Chip key={t} text={t} on={type === t} onPress={() => setType(t)} />)}
        </ScrollView>
        <View style={s.row}>{STATES.map(t => <Chip key={t} text={t} on={state === t} onPress={() => setState(t)} />)}</View>
        {state === '종료·해제' && <Text style={s.muted}>종료·해제된 재난은 종료 시점부터 90일간 보관돼요.</Text>}
        {list.length === 0 && <Empty icon="search-outline" text="조건에 맞는 재난 정보가 없어요." />}
        {list.map(d => <DisasterCard key={d.id} d={d} starred={areas.includes(d.region)} onPress={() => router.push(`/disasters/${d.id}`)} />)}
      </Page>
    </View>
  );
}
