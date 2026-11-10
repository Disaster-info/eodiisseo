// FS-LOC-003: 위치 직접 지정 (지역 검색 + TMAP 장소 검색)
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { ActivityIndicator, Text, TextInput, View } from 'react-native';
import { Card, Empty, Header, Icon, Page, useTheme } from '../../components/ui';
import { REGIONS } from '../../data/mock';
import { Poi, searchPois } from '../../data/tmap';
import { useApp } from '../../store/AppContext';

export default function ManualLocationScreen() {
  const { C, s } = useTheme();
  const { pickLocation, resetToGps, locConsent, isManual, needLocation, location } = useApp();
  const [q, setQ] = useState('');
  const list = REGIONS.filter(r => r.includes(q.trim()));
  // 장소 검색 결과. null = 아직 검색 안 함 / 'loading' / 'error'
  const [pois, setPois] = useState<Poi[] | null | 'loading' | 'error'>(null);
  const searchId = useRef(0); // 가장 최근 검색 번호 — 늦게 온 이전 검색 결과는 버림
  const search = () => {
    if (!q.trim()) return;
    const my = ++searchId.current;
    setPois('loading');
    searchPois(q.trim()).then(r => my === searchId.current && setPois(r), () => my === searchId.current && setPois('error'));
  };
  const isPicked = (r: string) => isManual && !needLocation && r === location;
  const done = () => (router.canGoBack() ? router.back() : router.replace('/'));
  return (
    <View style={s.fill}>
      <Header title="위치 직접 지정" />
      <Page>
        {needLocation && <Text style={s.notice}>위치정보 이용에 동의하지 않아 기준 위치를 직접 지정해야 해요.</Text>}
        <Text style={s.muted}>기준으로 사용할 지역을 선택하세요 (시·군·구)</Text>
        <TextInput accessibilityLabel="지역 검색" style={[s.input, { backgroundColor: C.card }]} value={q} onChangeText={v => { setQ(v); setPois(null); searchId.current++; }}
          onSubmitEditing={search} returnKeyType="search" placeholder="지역·장소 검색 (예: 강남, 수원역)" placeholderTextColor={C.sub} />
        {locConsent && (
          <Card onPress={() => { resetToGps(); done(); }}>
            <View style={s.row}>
              <Icon name={isManual ? 'navigate-outline' : 'navigate'} size={22} color={C.primary} />
              <Text style={[s.body, { flex: 1, color: C.primary, fontWeight: '700' }]}>현재 위치 사용</Text>
              {!isManual && <Icon name="checkmark" size={20} color={C.primary} />}
            </View>
          </Card>
        )}
        {list.length === 0 && pois === null && <Empty icon="search-outline" text="확인할 수 없는 지역이에요. 시·군·구 이름으로 검색해 주세요." />}
        {list.map(r => (
          <Card key={r} onPress={() => { pickLocation(r); done(); }}>
            <View style={s.row}>
              <Icon name={isPicked(r) ? 'radio-button-on' : 'radio-button-off'} size={22} color={isPicked(r) ? C.primary : C.sub} />
              <Text style={[s.body, isPicked(r) && s.bold]}>{r}</Text>
            </View>
          </Card>
        ))}
        {pois === 'loading' && <ActivityIndicator color={C.primary} accessibilityLabel="장소 검색 중" />}
        {pois === 'error' && <Text style={s.notice}>장소 검색에 실패했어요. 잠시 후 다시 시도해 주세요.</Text>}
        {Array.isArray(pois) && pois.length === 0 && <Empty icon="search-outline" text="검색된 장소가 없어요." />}
        {Array.isArray(pois) && pois.map(p => (
          <Card key={p.id} onPress={() => { pickLocation(p.region, { name: p.name, lat: p.lat, lon: p.lon }); done(); }}>
            <View style={s.row}>
              <Icon name="location-outline" size={22} color={C.sub} />
              <View style={{ flex: 1 }}>
                <Text style={s.body}>{p.name}</Text>
                <Text style={s.muted}>{p.addr}</Text>
              </View>
            </View>
          </Card>
        ))}
      </Page>
    </View>
  );
}
