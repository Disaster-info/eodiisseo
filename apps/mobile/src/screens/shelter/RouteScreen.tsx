// FS-ROUTE-001: 대피 경로 — 경로 API 기본 경로 그대로 표시, API 가 준 정보만 보여줌
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { Btn, Card, Header, Icon, InfoRow, MapBox, Page, useTheme, Segmented } from '../../components/ui';
import { ROUTES, SHELTERS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function RouteScreen({ id }: { id: number }) {
  const { C, s } = useTheme();
  const { location, locConsent } = useApp();
  const [useCurrent, setUseCurrent] = useState(locConsent);
  const [tries, setTries] = useState(0);
  const [retrying, setRetrying] = useState(false);
  // 실제로는 경로 API 재호출. 목업은 같은 결과를 다시 읽음
  const retry = () => { setRetrying(true); setTimeout(() => { setRetrying(false); setTries(n => n + 1); }, 700); };
  const sh = SHELTERS.find(x => x.id === id);
  if (!sh) return <View style={s.fill}><Header title="대피 경로" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  const route = ROUTES[sh.id]; // 실제로는 경로 API 호출
  const blocked = useCurrent && !locConsent; // 현재 위치를 쓸 수 없으면 경로 요청 안 함

  return (
    <View style={s.fill}>
      <Header title="대피 경로" />
      <Page>
        <Segmented label="출발 위치" items={[{ value: 'gps', label: '현재 위치에서' }, { value: 'manual', label: `지정 위치 (${location})` }]}
          value={useCurrent ? 'gps' : 'manual'} onChange={v => setUseCurrent(v === 'gps')} />

        {blocked ? (
          <Card>
            <Text style={s.body}>위치정보 이용에 동의하지 않아 현재 위치를 출발지로 쓸 수 없어요.</Text>
            <View style={{ height: 10 }} />
            <Btn title="출발 위치 직접 지정" icon="pin-outline" outline onPress={() => { setUseCurrent(false); router.push('/location'); }} />
          </Card>
        ) : route ? (
          <>
            <MapBox>
              <View style={s.row}>
                <Icon name="radio-button-on" size={16} color={C.success} />
                <Text style={s.bold}>{useCurrent ? '현재 위치' : location}</Text>
                <Icon name="arrow-forward" size={16} color={C.sub} />
                <Icon name="flag" size={16} color={C.danger} />
                <Text style={s.bold}>{sh.name}</Text>
              </View>
              <Text style={s.muted}>경로선 표시 (지도 SDK 연동 예정)</Text>
            </MapBox>
            <Card>
              <InfoRow icon="walk-outline" text={`이동거리 ${route.km}km`} />
              {route.minutes !== undefined && <InfoRow icon="time-outline" text={`예상 소요 ${route.minutes}분 (경로 API 제공)`} />}
            </Card>
          </>
        ) : (
          <Card>
            <View style={[s.row, { alignItems: 'flex-start' }]}>
              <Icon name="alert-circle-outline" size={20} color={C.danger} />
              <Text style={[s.body, { flex: 1 }]}>경로를 찾을 수 없어요.{tries ? ` (${tries}번 다시 시도함)` : ''} 아래 주소로 직접 이동하거나 다른 대피소를 선택하세요.</Text>
            </View>
            <View style={[s.row, { marginTop: 10 }]}>
              <View style={{ flex: 1 }}>{retrying
                ? <View style={[s.btn, s.row]}><ActivityIndicator color={C.primary} /><Text style={s.muted}>다시 찾는 중…</Text></View>
                : <Btn title="다시 시도" icon="refresh" onPress={retry} />}</View>
              <View style={{ flex: 1 }}><Btn title="다른 대피소" outline onPress={() => router.back()} /></View>
            </View>
          </Card>
        )}

        <Card>
          <InfoRow icon="shield-checkmark-outline" text={sh.name} />
          <InfoRow icon="location-outline" text={sh.addr} />
        </Card>
        <View style={[s.row, { alignItems: 'flex-start' }]}>
          <Icon name="information-circle-outline" size={16} color={C.warning} />
          <Text style={[s.muted, { flex: 1, marginTop: 0 }]}>지도 서비스가 제공하는 추천 경로이며 안전을 보장하는 최적 경로가 아니에요. 현장 안내를 우선하세요.</Text>
        </View>
      </Page>
    </View>
  );
}
