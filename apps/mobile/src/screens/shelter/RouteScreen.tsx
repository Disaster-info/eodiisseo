// FS-ROUTE-001: 대피 경로 — TMAP 보행자 경로 API 기본 경로 그대로 표시, API 가 준 정보(경로선·거리·시간)만 보여줌
// 출발: '내 위치'(동의했으면 GPS, 직접 지정했으면 그 위치) 또는 '그룹원 위치'(안내할 수 있는 그룹원만 선택)
// C안 '탑승권': 출발 위치 선택은 남색 띠 위 칩, 경로는 대피 승차권 한 장(출발 → 도착 · 절취선 · 도보 거리·소요 시간)
import * as Location from 'expo-location';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View, useWindowDimensions } from 'react-native';
import { TmapView } from '../../components/map';
import { BandTabs, Coupon, Field, Perf } from '../../components/ticket';
import { Btn, Header, Icon, Page, STALE_MIN, useTheme } from '../../components/ui';
import { MAX_RADIUS, ME, Member, SHELTERS } from '../../data/mock';
import { withTimeout } from '../../data/api';
import { LatLon, Route, coordsOf, distanceKm, searchRoute } from '../../data/tmap';
import { useApp } from '../../store/AppContext';

type Found = Route & { from: LatLon };
type Mode = 'me' | 'member';

// 그룹원을 출발지로 쓸 수 없는 이유 (null = 가능). 공유 꺼짐·오래된 위치·대피소에서 너무 멀면 안내하지 않음
function blockReason(m: Member, to: LatLon): string | null {
  if (!m.sharing || m.lat === undefined || m.lon === undefined) return '위치 공유 꺼짐';
  if ((m.updatedMin ?? 0) > STALE_MIN) return `${m.updatedMin}분 전 위치라 안내할 수 없어요`;
  const km = distanceKm({ lat: m.lat, lon: m.lon }, to);
  return km > MAX_RADIUS ? `이 대피소에서 ${Math.round(km)}km 떨어져 있어요` : null;
}

export default function RouteScreen({ id }: { id: number }) {
  const { C, s } = useTheme();
  const { location, locConsent, isManual, place, groups, member } = useApp();
  const [mode, setMode] = useState<Mode>('me');
  const [pickedKey, setPickedKey] = useState<string | null>(null);
  const [tries, setTries] = useState(0);
  // 'denied' = 휴대폰 위치 권한 거부. key = 어떤 조건으로 요청한 결과인지 → 지금 조건과 다르면 아직 찾는 중
  const [res, setRes] = useState<{ key: string; value: Found | 'error' | 'denied' } | null>(null);
  // 지도 높이: 대피소 탭과 같게 화면 높이의 38% (240~440)
  const mapHeight = Math.round(Math.min(440, Math.max(240, useWindowDimensions().height * 0.38)));
  const sh = SHELTERS.find(x => x.id === id);

  // 내 위치 = 위치 동의 + 직접 지정 안 함이면 GPS, 아니면 지정 위치 (같은 위치를 두 버튼으로 보여주지 않음)
  const gps = locConsent && !isManual;
  const myName = gps ? '현재 위치' : place?.name ?? location;
  // 그룹원 목록 (나 제외). 안내할 수 없는 사람은 이유와 함께 흐리게
  const members = groups.flatMap(g => g.members.filter(m => m.id !== ME)
    .map(m => ({ key: `${g.id}:${m.id}`, m, group: g.name, reason: sh ? blockReason(m, sh) : '대피소 없음' })));
  const usable = members.filter(x => !x.reason);
  const picked = mode === 'member' ? usable.find(x => x.key === pickedKey) ?? usable[0] : undefined;
  const waiting = mode === 'member' && !picked; // 안내할 수 있는 그룹원이 없음 → 경로 요청 안 함

  const origin = mode === 'me' ? (gps ? 'gps' : place ? `${place.lat},${place.lon}` : location) : picked ? `${picked.m.lat},${picked.m.lon}` : '-';
  const key = `${id}|${origin}|${tries}`;
  const result = res?.key === key ? res.value : 'loading';

  useEffect(() => {
    if (!sh || waiting) return;
    let alive = true; // 화면을 떠나거나 조건이 바뀌면 늦게 온 응답은 버림
    (async (): Promise<Found | 'denied'> => {
      let from: LatLon;
      if (picked) from = { lat: picked.m.lat!, lon: picked.m.lon! };
      else if (gps) {
        if (!(await Location.requestForegroundPermissionsAsync()).granted) return 'denied';
        const { coords } = await withTimeout(Location.getCurrentPositionAsync({}), 20000, '현재 위치 확인 시간 초과'); // 늦으면 '경로를 찾을 수 없어요' + 다시 시도
        from = { lat: coords.latitude, lon: coords.longitude };
      } else from = place ?? (await coordsOf(location)); // 장소 검색으로 고른 곳, 지역만 골랐으면 그 지역 대표 좌표
      return { ...(await searchRoute(from, sh)), from };
    })().then(value => alive && setRes({ key, value }), () => alive && setRes({ key, value: 'error' }));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- key 가 출발지·재시도를 모두 담음 (picked 는 매번 새 객체)
  }, [key, sh, waiting]);

  if (!sh) return <View style={s.fill}><Header title="대피 경로" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  const startName = picked ? `${picked.m.name}님 위치` : myName;

  return (
    <View style={s.fill}>
      <Header title="대피 경로" />
      <Page band={member?.operator ? 28 : 64}>
        {/* 운영자는 안심그룹을 쓰지 않으므로 '그룹원 위치' 선택 없음 */}
        {!member?.operator && <BandTabs label="출발 위치" items={[{ value: 'me', label: '내 위치에서' }, { value: 'member', label: '그룹원 위치에서' }]}
          value={mode} onChange={v => setMode(v as Mode)} />}

        {mode === 'member' && (
          members.length === 0 ? (
            <Coupon head="출발할 그룹원" headIcon="people">
              <Text style={s.body}>안심그룹이 없어요. 가족·친구와 그룹을 만들면 그룹원 위치에서 대피소까지 경로를 안내할 수 있어요.</Text>
              <View style={{ height: 10 }} />
              <Btn title="안심그룹으로" icon="people-outline" outline color={C.tabOn} onPress={() => router.navigate('/group')} />
            </Coupon>
          ) : (
            <Coupon head="출발할 그룹원" headIcon="people" right={`${usable.length}/${members.length}명 가능`}>
              {members.map((x, i) => {
                const on = picked?.key === x.key;
                return (
                  <Pressable key={x.key} disabled={!!x.reason} onPress={() => setPickedKey(x.key)}
                    accessibilityRole="radio" accessibilityState={{ selected: on, disabled: !!x.reason }}
                    accessibilityLabel={`${x.m.name}, ${x.group}. ${x.reason ?? '선택 가능'}`}
                    style={({ pressed }) => [s.row, { gap: 12, minHeight: 56, paddingVertical: 8 },
                      i > 0 && s.divTop, pressed && s.pressed, !!x.reason && { opacity: 0.55 }]}>
                    <Icon name={on ? 'radio-button-on' : 'radio-button-off'} size={22} color={on ? C.tabOn : C.sub} />
                    <View style={{ flex: 1 }}>
                      <Text style={[s.body, on && s.bold]}>{x.m.name} <Text style={s.small}>· {x.group}</Text></Text>
                      <Text style={s.small}>{x.reason ?? `${x.m.updatedMin}분 전 위치 · 대피소까지 직선 ${distanceKm({ lat: x.m.lat!, lon: x.m.lon! }, sh).toFixed(1)}km`}</Text>
                    </View>
                  </Pressable>
                );
              })}
            </Coupon>
          )
        )}

        {result !== 'loading' && result !== 'error' && result !== 'denied' && !waiting && (
          <TmapView height={mapHeight} markers={[{ ...result.from, title: startName, me: true }, { lat: sh.lat, lon: sh.lon, title: sh.name }]} path={result.path}
            label={`${startName}에서 ${sh.name}까지 도보 경로 지도`} />
        )}

        {/* 대피 승차권: 출발 → 도착 / 절취선 / 경로 결과 (찾는 중·실패·권한 없음도 이 칸에서) */}
        <Coupon tone="green" head="대피 경로" headIcon="walk" right="도보">
          <View style={[s.row, { gap: 10, alignItems: 'flex-start' }]}>
            <View style={{ flex: 1 }} accessible accessibilityLabel={`출발 ${waiting ? '선택 안 됨' : startName}`}>
              <Text style={s.small}>출발{mode === 'me' ? (gps ? ' · 현재 위치(GPS)' : ' · 지정 위치') : ' · 그룹원'}</Text>
              <Text style={s.bold}>{waiting ? '—' : startName}</Text>
            </View>
            <Icon name="arrow-forward" size={22} color={C.sub} style={{ marginTop: 14 }} />
            <View style={{ flex: 1.4 }} accessible accessibilityLabel={`도착 ${sh.name}, ${sh.addr}`}>
              <Text style={s.small}>도착 · 대피소</Text>
              <Text style={[s.bold, { fontWeight: '900' }]} lineBreakStrategyIOS="hangul-word">{sh.name}</Text>
              <Text style={s.small}>{sh.addr}</Text>
            </View>
          </View>
          <Perf />
          {waiting ? (
            members.length > 0 ? <Text style={s.muted}>지금 경로를 안내할 수 있는 그룹원이 없어요. 위치를 공유 중이고 최근 위치가 이 대피소 {MAX_RADIUS}km 안에 있어야 해요.</Text>
              : <Text style={s.muted}>출발할 그룹원이 없어요.</Text>
          ) : result === 'denied' ? (
            <>
              <Text style={s.body}>휴대폰의 위치 권한이 꺼져 있어 현재 위치를 확인할 수 없어요. 설정에서 권한을 켜거나 출발 위치를 직접 지정하세요.</Text>
              <View style={{ height: 10 }} />
              <Btn title="출발 위치 직접 지정" icon="pin-outline" outline color={C.tabOn} onPress={() => router.push('/location')} />
            </>
          ) : result === 'loading' ? (
            <View style={[s.row, { minHeight: 48 }]}><ActivityIndicator color={C.tabOn} /><Text style={s.muted}>경로를 찾는 중…</Text></View>
          ) : result === 'error' ? (
            <>
              <View style={[s.row, { alignItems: 'flex-start' }]}>
                <Icon name="alert-circle-outline" size={20} color={C.danger} />
                <Text style={[s.body, { flex: 1 }]}>경로를 찾을 수 없어요.{tries ? ` (${tries}번 다시 시도함)` : ''} 위 주소로 직접 이동하거나 다른 대피소를 선택하세요.</Text>
              </View>
              <View style={[s.row, { marginTop: 10 }]}>
                <View style={{ flex: 1 }}><Btn title="다시 시도" icon="refresh" color={C.navy} onPress={() => setTries(n => n + 1)} /></View>
                <View style={{ flex: 1 }}><Btn title="다른 대피소" outline color={C.tabOn} onPress={() => (router.canGoBack() ? router.back() : router.navigate('/shelter'))} /></View>
              </View>
            </>
          ) : (
            <View style={[s.row, { gap: 12 }]}>
              <Field label="도보 이동거리" value={`${(result.meters / 1000).toFixed(1)}km`} mono />
              <Field label="예상 소요" value={`${Math.max(1, Math.round(result.seconds / 60))}분`} mono />
            </View>
          )}
        </Coupon>
        <View style={[s.row, { alignItems: 'flex-start' }]}>
          <Icon name="information-circle-outline" size={16} color={C.warning} />
          <Text style={[s.muted, { flex: 1, marginTop: 0 }]}>지도 서비스가 제공하는 추천 경로이며 안전을 보장하는 최적 경로가 아니에요. 현장 안내를 우선하세요.</Text>
        </View>
      </Page>
    </View>
  );
}
