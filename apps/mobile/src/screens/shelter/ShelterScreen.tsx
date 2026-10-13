// FS-SHELTER-001: 기준 위치 선택 → 1km 부터 1km 씩 반경 확대 검색 → 직선거리순 목록 + 지도 마커 연동
// 기준 위치: 현재 위치(GPS, 위치 동의 + 직접 지정 아님) / 지정 위치·관심지역(지역 대표 좌표)
// 대피소: API-SHELTER-001 (공공데이터 지진옥외대피장소). 반경 확대(1→5km)는 명세대로 서버가 자동으로 처리
import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, Text, View } from 'react-native';
import { ApiError } from '../../api/client';
import { fetchNearbyShelters, NearbyShelters } from '../../api/shelter';
import PlaceMap, { MapPin } from '../../components/PlaceMap';
import { Btn, C, Card, Chip, Empty, Icon, Page, s } from '../../components/ui';
import { REGION_CENTER } from '../../data/mock';
import { GPS_FAIL_TEXT, useGps } from '../../hooks/useGps';
import { useApp } from '../../store/AppContext';

const CURRENT = '__current__'; // 기준 위치 칩: 현재(또는 지정) 위치

type State = { status: 'loading' } | { status: 'ok'; data: NearbyShelters } | { status: 'error'; message: string };

// 1km 미만은 m, 이상은 km 소수 첫째 자리 (직선거리)
const formatDistance = (m: number) => (m < 1000 ? `${m}m` : `${(m / 1000).toFixed(1)}km`);

export default function ShelterScreen() {
  const { location, isManual, areas, areaNames } = useApp();
  const [base, setBase] = useState(CURRENT);
  const [sel, setSel] = useState<string | null>(null);
  const [state, setState] = useState<State>({ status: 'loading' });

  // 기준 좌표 정하기: 현재 위치 칩 + GPS 사용 가능 → GPS / 그 외 → 지역 대표 좌표
  const useGpsNow = base === CURRENT && !isManual;
  const gps = useGps(useGpsNow);
  const region = base === CURRENT || !areas.includes(base) ? location : base;
  const gpsFailed = useGpsNow && (gps.status === 'denied' || gps.status === 'outside' || gps.status === 'error');
  const coords = useGpsNow && gps.status === 'ok' ? gps.coords : (!useGpsNow || gpsFailed ? REGION_CENTER[region] : undefined);
  const waitingGps = useGpsNow && gps.status === 'loading';

  const pickBase = (r: string) => { setBase(r); setSel(null); };

  const load = useCallback(async () => {
    if (!coords) return;
    setState({ status: 'loading' });
    try {
      setState({ status: 'ok', data: await fetchNearbyShelters(coords) });
    } catch (e) {
      setState({ status: 'error', message: e instanceof ApiError ? e.message : '대피소 정보를 불러오지 못했어요.' });
    }
    // coords 는 매 렌더 새 객체일 수 있어 숫자 값으로 의존
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coords?.lat, coords?.lng]);
  useEffect(() => { load(); }, [load]);

  const list = state.status === 'ok' ? state.data.shelters : [];
  const radius = state.status === 'ok' ? state.data.radiusKm : 1;

  // 지도 핀: [기준 위치] + 목록 순서대로 대피소. 번호는 아래 목록 카드의 번호와 같음
  const offset = coords ? 1 : 0; // pins 인덱스 = 목록 인덱스 + offset
  const pins: MapPin[] = [
    ...(coords ? [{ ...coords, title: '기준 위치', kind: 'start' as const }] : []),
    ...list.map((sh, i) => ({ lat: sh.lat, lng: sh.lng, title: sh.name, kind: 'place' as const, label: String(i + 1) })),
  ];
  const selIdx = list.findIndex(x => x.id === sel);
  const selPin = selIdx < 0 ? null : selIdx + offset;

  const currentLabel = isManual ? `지정 위치 · ${location}` : '현재 위치';

  return (
    <Page>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
        <Chip text={currentLabel} on={base === CURRENT} onPress={() => pickBase(CURRENT)} />
        {/* 직접 지정한 위치와 같은 관심지역은 첫 칩과 겹치므로 뺌 */}
        {areas.filter(a => !(isManual && a === location)).map(a => <Chip key={a} text={areaNames[a] ?? a} on={base === a} onPress={() => pickBase(a)} />)}
      </ScrollView>

      {/* GPS 를 못 쓰면 지정 위치(지역 대표 좌표)로 대신 보여주고 사유를 알림 */}
      {gpsFailed && (
        <View style={[s.row, { alignItems: 'flex-start' }]}>
          <Icon name="information-circle-outline" size={16} color={C.warning} />
          <Text style={[s.muted, { flex: 1, marginTop: 0 }]}>
            {GPS_FAIL_TEXT[gps.status as keyof typeof GPS_FAIL_TEXT]}. {location} 기준으로 보여드려요.
          </Text>
        </View>
      )}

      {/* 기준 위치(초록 핀) + 대피소 번호 마커. 마커를 누르면 아래 목록의 같은 대피소가 선택됨 */}
      {pins.length > 0 ? (
        <PlaceMap pins={pins} height={240} selected={selPin} onPinPress={i => {
          const sh = list[i - offset];
          if (sh) setSel(sel === sh.id ? null : sh.id);
        }} />
      ) : (
        <View style={[s.map, { height: 200 }]}>
          {waitingGps ? <><ActivityIndicator color={C.primary} /><Text style={s.muted}>현재 위치를 확인하는 중이에요…</Text></>
            : <Text style={s.muted}>이 지역은 지도에 표시할 위치 정보가 없어요.</Text>}
        </View>
      )}

      <View style={s.row}>
        <Text style={[s.section, { flex: 1 }]}>반경 {radius}km 내 대피소</Text>
        <Text style={[s.muted, { marginTop: 8 }]}>직선거리순</Text>
      </View>

      {(state.status === 'loading' || waitingGps) && coords !== undefined && (
        <Card><View style={s.row}><ActivityIndicator color={C.primary} /><Text style={s.body}>대피소를 찾는 중이에요…</Text></View></Card>
      )}
      {state.status === 'error' && (
        <Card>
          <View style={s.row}>
            <Icon name="alert-circle-outline" size={20} color={C.danger} />
            <Text style={[s.body, { flex: 1 }]}>대피소 정보를 불러오지 못했어요.</Text>
          </View>
          <Text style={s.muted}>{state.message}</Text>
          <View style={{ height: 10 }} />
          <Btn title="다시 시도" icon="refresh" outline onPress={load} />
        </Card>
      )}
      {state.status === 'ok' && list.length === 0 && (
        <Empty icon="shield-outline" text={`최대 반경 ${radius}km 안에 등록된 대피소가 없어요.\n다른 기준 위치를 선택해 보세요.`} />
      )}

      {list.map((sh, i) => (
        <Card key={sh.id} onPress={() => setSel(sel === sh.id ? null : sh.id)}>
          <View style={[s.row, { gap: 12 }]}>
            <View style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: sel === sh.id ? C.danger : C.primarySoft, justifyContent: 'center', alignItems: 'center' }}>
              <Text style={{ fontWeight: '800', color: sel === sh.id ? '#fff' : C.primary }}>{i + 1}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={s.bold}>{sh.name}</Text>
              <Text style={s.muted} numberOfLines={1}>{sh.address}</Text>
            </View>
            <Text style={[s.bold, { color: C.primary }]}>{formatDistance(sh.distanceMeters)}</Text>
          </View>
          {sel === sh.id && (
            <View style={[s.row, { marginTop: 12 }]}>
              <View style={{ flex: 1 }}><Btn title="상세정보" outline onPress={() => router.push(`/shelters/${sh.id}`)} /></View>
              <View style={{ flex: 1 }}><Btn title="경로 보기" icon="navigate" onPress={() => router.push(`/route/${sh.id}`)} /></View>
            </View>
          )}
        </Card>
      ))}
    </Page>
  );
}
