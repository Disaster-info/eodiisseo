// FS-ROUTE-001: 대피 경로 — TMAP 보행자 경로 API 기본 경로 그대로 표시, API 가 준 정보(경로선·거리·시간)만 보여줌
import * as Location from 'expo-location';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { TmapView } from '../../components/map';
import { Btn, Card, Header, Icon, InfoRow, Page, useTheme, Segmented } from '../../components/ui';
import { SHELTERS } from '../../data/mock';
import { LatLon, Route, coordsOf, searchRoute } from '../../data/tmap';
import { useApp } from '../../store/AppContext';

type Found = Route & { from: LatLon };

export default function RouteScreen({ id }: { id: number }) {
  const { C, s } = useTheme();
  const { location, locConsent, isManual, place } = useApp();
  // 기준 위치를 직접 지정했으면 그곳에서 출발이 기본. 지정하지 않았으면 현재 위치(동의한 경우)
  const [useCurrent, setUseCurrent] = useState(locConsent && !isManual);
  const [tries, setTries] = useState(0);
  // 'denied' = 휴대폰 위치 권한 거부. key = 어떤 조건으로 요청한 결과인지 → 지금 조건과 다르면 아직 찾는 중
  const [res, setRes] = useState<{ key: string; value: Found | 'error' | 'denied' } | null>(null);
  const sh = SHELTERS.find(x => x.id === id);
  const blocked = useCurrent && !locConsent; // 현재 위치를 쓸 수 없으면 경로 요청 안 함

  const key = `${id}|${useCurrent ? 'gps' : place ? `${place.lat},${place.lon}` : location}|${tries}`;
  const result = res?.key === key ? res.value : 'loading';

  useEffect(() => {
    if (!sh || blocked) return;
    let alive = true; // 화면을 떠나거나 조건이 바뀌면 늦게 온 응답은 버림
    (async (): Promise<Found | 'denied'> => {
      let from: LatLon;
      if (useCurrent) {
        if (!(await Location.requestForegroundPermissionsAsync()).granted) return 'denied';
        const { coords } = await Location.getCurrentPositionAsync({});
        from = { lat: coords.latitude, lon: coords.longitude };
      } else from = place ?? (await coordsOf(location)); // 장소 검색으로 고른 곳, 지역만 골랐으면 그 지역 대표 좌표
      return { ...(await searchRoute(from, sh)), from };
    })().then(value => alive && setRes({ key, value }), () => alive && setRes({ key, value: 'error' }));
    return () => { alive = false; };
  }, [key, sh, useCurrent, place, location, blocked]);

  if (!sh) return <View style={s.fill}><Header title="대피 경로" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  const startName = useCurrent ? '현재 위치' : place?.name ?? location;

  return (
    <View style={s.fill}>
      <Header title="대피 경로" />
      <Page>
        <Segmented label="출발 위치" items={[{ value: 'gps', label: '현재 위치에서' }, { value: 'manual', label: `지정 위치 (${place?.name ?? location})` }]}
          value={useCurrent ? 'gps' : 'manual'} onChange={v => setUseCurrent(v === 'gps')} />

        {blocked ? (
          <Card>
            <Text style={s.body}>위치정보 이용에 동의하지 않아 현재 위치를 출발지로 쓸 수 없어요.</Text>
            <View style={{ height: 10 }} />
            <Btn title="출발 위치 직접 지정" icon="pin-outline" outline onPress={() => { setUseCurrent(false); router.push('/location'); }} />
          </Card>
        ) : result === 'denied' ? (
          <Card>
            <Text style={s.body}>휴대폰의 위치 권한이 꺼져 있어 현재 위치를 확인할 수 없어요. 설정에서 권한을 켜거나 지정 위치에서 출발하세요.</Text>
            <View style={{ height: 10 }} />
            <Btn title="지정 위치에서 출발" icon="pin-outline" outline onPress={() => setUseCurrent(false)} />
          </Card>
        ) : result === 'loading' ? (
          <View style={[s.map, s.row]}><ActivityIndicator color={C.primary} /><Text style={s.muted}>경로를 찾는 중…</Text></View>
        ) : result === 'error' ? (
          <Card>
            <View style={[s.row, { alignItems: 'flex-start' }]}>
              <Icon name="alert-circle-outline" size={20} color={C.danger} />
              <Text style={[s.body, { flex: 1 }]}>경로를 찾을 수 없어요.{tries ? ` (${tries}번 다시 시도함)` : ''} 아래 주소로 직접 이동하거나 다른 대피소를 선택하세요.</Text>
            </View>
            <View style={[s.row, { marginTop: 10 }]}>
              <View style={{ flex: 1 }}><Btn title="다시 시도" icon="refresh" onPress={() => setTries(n => n + 1)} /></View>
              <View style={{ flex: 1 }}><Btn title="다른 대피소" outline onPress={() => router.back()} /></View>
            </View>
          </Card>
        ) : (
          <>
            <TmapView markers={[{ ...result.from, title: startName }, { lat: sh.lat, lon: sh.lon, title: sh.name }]} path={result.path}
              label={`${startName}에서 ${sh.name}까지 도보 경로 지도`} />
            <Card>
              <InfoRow icon="walk-outline" text={`도보 이동거리 ${(result.meters / 1000).toFixed(1)}km`} />
              <InfoRow icon="time-outline" text={`예상 소요 ${Math.max(1, Math.round(result.seconds / 60))}분`} />
            </Card>
          </>
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
