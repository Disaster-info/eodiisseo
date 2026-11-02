// FS-LOC-003: 위치 직접 지정 (지역 검색)
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { Card, Empty, Header, Icon, Page, useTheme } from '../../components/ui';
import { REGIONS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ManualLocationScreen() {
  const { C, s } = useTheme();
  const { pickLocation, resetToGps, locConsent, isManual, needLocation, location } = useApp();
  const [q, setQ] = useState('');
  const list = REGIONS.filter(r => r.includes(q.trim()));
  const isPicked = (r: string) => isManual && !needLocation && r === location;
  const done = () => (router.canGoBack() ? router.back() : router.replace('/'));
  return (
    <View style={s.fill}>
      <Header title="위치 직접 지정" />
      <Page>
        {needLocation && <Text style={s.notice}>위치정보 이용에 동의하지 않아 기준 위치를 직접 지정해야 해요.</Text>}
        <Text style={s.muted}>기준으로 사용할 지역을 선택하세요 (시·군·구)</Text>
        <TextInput accessibilityLabel="지역 검색" style={[s.input, { backgroundColor: C.card }]} value={q} onChangeText={setQ} placeholder="지역 검색 (예: 강남)" placeholderTextColor={C.sub} />
        {locConsent && (
          <Card onPress={() => { resetToGps(); done(); }}>
            <View style={s.row}>
              <Icon name={isManual ? 'navigate-outline' : 'navigate'} size={22} color={C.primary} />
              <Text style={[s.body, { flex: 1, color: C.primary, fontWeight: '700' }]}>현재 위치 사용</Text>
              {!isManual && <Icon name="checkmark" size={20} color={C.primary} />}
            </View>
          </Card>
        )}
        {list.length === 0 && <Empty icon="search-outline" text="확인할 수 없는 지역이에요. 시·군·구 이름으로 검색해 주세요." />}
        {list.map(r => (
          <Card key={r} onPress={() => { pickLocation(r); done(); }}>
            <View style={s.row}>
              <Icon name={isPicked(r) ? 'radio-button-on' : 'radio-button-off'} size={22} color={isPicked(r) ? C.primary : C.sub} />
              <Text style={[s.body, isPicked(r) && s.bold]}>{r}</Text>
            </View>
          </Card>
        ))}
      </Page>
    </View>
  );
}
