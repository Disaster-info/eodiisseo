// FS-LOC-003: 위치 직접 지정 (지역 검색)
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { C, Card, Empty, Header, Icon, Page, s } from '../../components/ui';
import { REGIONS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ManualLocationScreen() {
  const { setLocation, location } = useApp();
  const [q, setQ] = useState('');
  const list = REGIONS.filter(r => r.includes(q.trim()));
  return (
    <View style={s.fill}>
      <Header title="위치 직접 지정" />
      <Page>
        <Text style={s.muted}>기준으로 사용할 지역을 선택하세요 (시·군·구)</Text>
        <TextInput style={[s.input, { backgroundColor: C.card }]} value={q} onChangeText={setQ} placeholder="지역 검색 (예: 강남)" placeholderTextColor={C.sub} />
        {list.length === 0 && <Empty icon="search-outline" text="확인할 수 없는 지역이에요. 시·군·구 이름으로 검색해 주세요." />}
        {list.map(r => (
          <Card key={r} onPress={() => { setLocation(r); if (router.canGoBack()) router.back(); else router.replace('/'); }}>
            <View style={s.row}>
              <Icon name={r === location ? 'radio-button-on' : 'radio-button-off'} size={22} color={r === location ? C.primary : C.sub} />
              <Text style={[s.body, r === location && s.bold]}>{r}</Text>
            </View>
          </Card>
        ))}
      </Page>
    </View>
  );
}
