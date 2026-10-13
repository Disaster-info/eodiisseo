// FS-ROUTE-001: 대피 경로 — API-ROUTE-001 기본 경로 그대로 표시, API 가 준 정보만 보여줌
import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { ApiError } from '../../api/client';
import { fetchShelterRoute, ShelterRoute } from '../../api/route';
import { fetchShelterDetail, ShelterDetail } from '../../api/shelter';
import PlaceMap from '../../components/PlaceMap';
import { Btn, C, Card, Chip, Header, Icon, InfoRow, Page, s } from '../../components/ui';
import { REGION_CENTER } from '../../data/mock';
import { GPS_FAIL_TEXT, useGps } from '../../hooks/useGps';
import { useApp } from '../../store/AppContext';

// 화면 흐름: 진입 → 경로 요청(loading) → 성공(ok: 지도+거리·시간+안내) / 실패(error: 사유+다시 시도)
// 출발 좌표: "현재 위치에서" = GPS, "지정 위치" = 지역 대표 좌표(REGION_CENTER) / 도착: shelterId (서버가 대피소 좌표 확인)
// 대피소 정보(API-SHELTER-002)는 경로와 따로 불러와서, 경로가 실패해도 대피소 정보는 보여줌 (FR-ROUTE-005)
type State = { status: 'loading' } | { status: 'ok'; route: ShelterRoute } | { status: 'error'; message: string };

const km = (m: number) => `${(m / 1000).toFixed(1)}km`;

export default function RouteScreen({ id }: { id: string }) {
  const { location, locConsent } = useApp();
  const [useCurrent, setUseCurrent] = useState(locConsent);
  const [state, setState] = useState<State>({ status: 'loading' });
  const [sh, setSh] = useState<ShelterDetail | null>(null);
  const [shMissing, setShMissing] = useState(false); // 서버에 없는 대피소 (DB 연동 전: 서버 재시작 후 목록 재조회 필요)
  const blocked = useCurrent && !locConsent; // 현재 위치를 쓸 수 없으면 경로 요청 안 함
  const gps = useGps(useCurrent && locConsent);
  const start = useCurrent ? (gps.status === 'ok' ? gps.coords : undefined) : REGION_CENTER[location];

  // 대피소 정보 (화면 진입 시 한 번). 실패해도 경로 조회는 따로 진행
  useEffect(() => {
    let alive = true;
    fetchShelterDetail(id)
      .then(d => { if (alive) setSh(d); })
      .catch(e => { if (alive && e instanceof ApiError && e.code === 'SHELTER_NOT_FOUND') setShMissing(true); });
    return () => { alive = false; };
  }, [id]);

  // 경로 요청. 칩(현재 위치/지정 위치)을 바꾸면 start 가 바뀌어 다시 요청됨
  const load = useCallback(async () => {
    if (shMissing || blocked) return;
    if (useCurrent && gps.status === 'loading') { setState({ status: 'loading' }); return; } // GPS 기다리는 중
    if (useCurrent && gps.status !== 'ok') {
      const why = GPS_FAIL_TEXT[gps.status as keyof typeof GPS_FAIL_TEXT] ?? '현재 위치를 쓸 수 없어요';
      setState({ status: 'error', message: `${why}. '지정 위치'로 바꿔 보세요.` });
      return;
    }
    if (!start) { setState({ status: 'error', message: `${location}의 출발 좌표가 없어 경로를 조회할 수 없어요.` }); return; }
    setState({ status: 'loading' });
    try {
      setState({ status: 'ok', route: await fetchShelterRoute(id, start) });
    } catch (e) {
      // FS-ROUTE-005: 경로 실패해도 아래 대피소 정보는 그대로 보여줌
      setState({ status: 'error', message: e instanceof ApiError ? e.message : '경로를 불러오지 못했어요.' });
    }
    // start 는 매 렌더 새 객체일 수 있어 숫자 값으로 의존
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, shMissing, blocked, useCurrent, gps.status, start?.lat, start?.lng, location]);

  useEffect(() => { load(); }, [load]); // 화면 진입 시 + 출발지 변경 시 자동 조회

  if (shMissing) return <View style={s.fill}><Header title="대피 경로" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.{'\n'}대피소 목록에서 다시 선택해 주세요.</Text></View>;

  return (
    <View style={s.fill}>
      <Header title="대피 경로" />
      <Page>
        <View style={s.row}>
          <Chip text="현재 위치에서" on={useCurrent} onPress={() => setUseCurrent(true)} />
          <Chip text={`지정 위치 (${location})`} on={!useCurrent} onPress={() => setUseCurrent(false)} />
        </View>

        {blocked ? (
          <Card>
            <Text style={s.body}>위치정보 이용에 동의하지 않아 현재 위치를 출발지로 쓸 수 없어요.</Text>
            <View style={{ height: 10 }} />
            <Btn title="출발 위치 직접 지정" icon="pin-outline" outline onPress={() => router.push('/location')} />
          </Card>
        ) : state.status === 'loading' ? (
          <Card>
            <View style={s.row}>
              <ActivityIndicator color={C.primary} />
              <Text style={s.body}>경로를 찾는 중이에요…</Text>
            </View>
          </Card>
        ) : state.status === 'error' ? (
          <Card>
            <View style={s.row}>
              <Icon name="alert-circle-outline" size={20} color={C.danger} />
              <Text style={[s.body, { flex: 1 }]}>경로를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.</Text>
            </View>
            <Text style={s.muted}>{state.message}</Text>
            <View style={{ height: 10 }} />
            <Btn title="다시 시도" icon="refresh" outline onPress={load} />
          </Card>
        ) : (
          <>
            <PlaceMap path={state.route.path} pins={[
              { ...state.route.origin, title: '출발', kind: 'start' },
              { ...state.route.destination, title: state.route.destination.name, kind: 'end' },
            ]} />
            <View style={s.row}>
              <Icon name="radio-button-on" size={16} color={C.success} />
              <Text style={s.bold}>{useCurrent ? '현재 위치' : location}</Text>
              <Icon name="arrow-forward" size={16} color={C.sub} />
              <Icon name="flag" size={16} color={C.danger} />
              <Text style={s.bold}>{state.route.destination.name}</Text>
            </View>
            {/* 거리·시간은 제공자가 안 주면 null — 임의로 만들지 않고 줄을 숨김 */}
            {(state.route.distanceMeters != null || state.route.durationSeconds != null) && (
              <Card>
                {state.route.distanceMeters != null && <InfoRow icon="walk-outline" text={`이동거리 ${km(state.route.distanceMeters)}`} />}
                {state.route.durationSeconds != null && <InfoRow icon="time-outline" text={`예상 소요 ${Math.ceil(state.route.durationSeconds / 60)}분 (경로 API 제공)`} />}
              </Card>
            )}
            {state.route.guides.length > 0 && (
              <Card>
                <Text style={s.bold}>경로 안내</Text>
                {state.route.guides.map((g, i) => (
                  <Text key={i} style={s.muted}>{i + 1}. {g.description}</Text>
                ))}
              </Card>
            )}
          </>
        )}

        {sh && (
          <Card>
            <InfoRow icon="shield-checkmark-outline" text={sh.name} />
            <InfoRow icon="location-outline" text={sh.address} />
          </Card>
        )}
        <View style={[s.row, { alignItems: 'flex-start' }]}>
          <Icon name="information-circle-outline" size={16} color={C.warning} />
          <Text style={[s.muted, { flex: 1, marginTop: 0 }]}>지도 서비스가 제공하는 추천 경로이며 안전을 보장하는 최적 경로가 아니에요. 현장 안내를 우선하세요.</Text>
        </View>
      </Page>
    </View>
  );
}
