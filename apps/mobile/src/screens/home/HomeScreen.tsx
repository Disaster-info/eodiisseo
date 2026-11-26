// 대시보드 (D안 '전면 상황판'): FS-WEATHER-001 날씨, FS-DASH-001 주요 재난, 관심지역 재난 우선 표시
// 지금 상황 하나가 화면 전체를 차지하고, 위로 넘기면 대피소 → 가족 순서 (상태가 곧 화면 색)
//   1쪽 상황: 기준 위치 · 날씨(지역 선택) · 현재 위치 경보·주의보 + 관심지역 경보·주의보 · 오른쪽 행동 버튼(행동요령·원문)
//   2쪽 대피소: 가장 가까운 대피소(경로·지도) + 다음 대피소 2곳(누르면 경로)
//   3쪽 가족: 안심그룹 영향상태 (운영자는 안심그룹을 쓰지 않으므로 없음)
// 다른 지역 재난은 1쪽 아래 카드의 '다른 지역 N건' → 재난 목록 화면에서 봄
// 넘기는 동작을 모르는 사용자를 위해 오른쪽 쪽 표시를 눌러도 이동. 탭 바의 '홈'을 누르면 언제나 1쪽으로
import * as Clipboard from 'expo-clipboard';
import { router, useIsFocused } from 'expo-router';
import { ReactNode, createContext, useContext, useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View, type ScrollViewInstance } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, IconName, STALE_MIN, ageMin, freshness, knownImpact, notify, useLayout, useTheme } from '../../components/ui';
import { fetchDisasters, fetchShelterRoute, fetchWeather, sameRegion, useLoad, useShelters } from '../../data/server';
import { Disaster, IMPACT_ICON, Impact, LEVEL_ICON, MAX_RADIUS, TYPE_ICON, WEATHER_ICON, isActive } from '../../data/types';
import { TmapView } from '../../components/map';
import { LatLon, Route, coordsOf } from '../../data/tmap';
import { useApp } from '../../store/AppContext';

const CURRENT = '현재 위치';
const TILE_MAX = 4; // 가족 칸은 위험한 순 4명까지, 나머지는 그룹 상세에서
const RANK = { 경보: 0, 주의보: 1, 정보: 2 } as const;
const rank = (d: Disaster) => (d.level ? RANK[d.level] : 3);
const IMPACT_ORDER: Record<Impact, number> = { '영향지역 포함': 0, '판단 불가': 1, '위치정보 미제공': 2, '영향지역 밖': 3 };

// 쪽마다 바탕색 (색면 + 오른쪽 위 밝은 번짐). 글자는 한 가지 색, 위계는 크기·굵기로
// 1쪽은 현재 위치 상태가 곧 화면 색: 경보 = 빨강, 주의보 = 노랑, 둘 다 없음 = 초록 ('차분한 원색' 안)
//   라이트: 원색에서 채도만 낮춰 덜 쨍하게. 주의보 노랑은 밝은 바탕이라 어두운 글자
//   다크: 같은 색상에서 채도 ×0.8 · 명도 27% 로 낮추고 글자도 한 톤 낮춘 흰색 (대비 완화). 글자 대비는 모두 4.5:1 이상
const NOW_FIELD = {
  light: { 경보: { bg: '#AE3429', glow: '#D0463A' }, 주의보: { bg: '#FFD43B', glow: '#FFE27A' }, 안전: { bg: '#28714F', glow: '#359769' } },
  dark: { 경보: { bg: '#672823', glow: '#85342D' }, 주의보: { bg: '#6C571D', glow: '#8C7126' }, 안전: { bg: '#2B5F47', glow: '#387B5C' } },
};
// 기준 위치를 모를 때(GPS 확인 중·실패): 안전(초록)으로 보이지 않게 회색
const UNKNOWN_FIELD = { light: { bg: '#4A5563', glow: '#5E6B7B' }, dark: { bg: '#2E343C', glow: '#3D4550' } };
const FIELD = {
  shelter: { bg: '#0B4A6B', glow: '#1C6E96' }, family: { bg: '#18223D', glow: '#2A3A63' }, // 대피소 = 페트롤 블루 (1쪽 초록과 구분되는 '보호' 색)
};
const INK = '#F6F1EC';
const SOFT = '#E2D9D3'; // 보조 글자 (모든 바탕에서 대비 7:1 이상)
const GO = '#2FA36B';   // 대피(경로) 버튼
const HIT = '#FF9C92';  // 어두운 바탕 위 '영향지역' 강조
// 글자 톤: dark = 어두운 바탕 위 밝은 글자, light = 노란 바탕 위 어두운 글자. 아래 부품(버튼·칩·카드)은 쪽의 톤을 따름
type Tone = { bg: string; ink: string; soft: string; faint: string; line: string; card: string; btn: string };
const DARK_TONE: Tone = { bg: '#1E2125', ink: INK, soft: SOFT, faint: 'rgba(246,241,236,0.45)', line: 'rgba(246,241,236,0.2)', card: 'rgba(8,8,10,0.55)', btn: 'rgba(246,241,236,0.12)' };
const LIGHT_TONE: Tone = { bg: '#F2C230', ink: '#1F1803', soft: '#3A300C', faint: 'rgba(31,24,3,0.5)', line: 'rgba(31,24,3,0.2)', card: 'rgba(255,255,255,0.4)', btn: 'rgba(31,24,3,0.08)' };
// 라이트 노랑(주의보, #FFD43B) 1쪽은 LIGHT_TONE (어두운 글자)
const NOW_TONE: Tone = { ...DARK_TONE, soft: '#F0EAE3' };                // 라이트 1쪽 빨강·초록 (보조 글자를 밝혀 대비 4.5 이상)
const DIM_TONE: Tone = { ...DARK_TONE, ink: '#E3DDD4', soft: '#DDD6CC' }; // 다크 1쪽: 한 톤 낮춘 흰 글자
const ToneCtx = createContext(DARK_TONE);
// 등급 표지 (원색 판): 경보 빨강 · 주의보 노랑 · 정보 파랑
const TAG = { 경보: { bg: '#D7261E', fg: '#FFFFFF' }, 주의보: { bg: '#F5C400', fg: '#16181B' }, 정보: { bg: '#0B5CAD', fg: '#FFFFFF' } };

// 탭 바의 '홈'을 누르면 (탭 레이아웃의 tabPress 에서 부름): 이미 홈을 보고 있으면 1쪽으로,
// 다른 탭에서 돌아오는 거면 보던 쪽 그대로 (한 번 더 누르면 1쪽). 홈이 그려지기 전에는 아무것도 하지 않음
let toFirstPage = () => {};
export const goHomeFirstPage = () => toFirstPage();

export default function HomeScreen() {
  const { location, locState, isManual, areas, groups, groupsState, reloadGroups, member, basePos, myPos } = useApp();
  const insets = useSafeAreaInsets();
  const { gutter } = useLayout();
  const { dark } = useTheme(); // 1쪽 상태 색은 기기 테마(라이트·다크)를 따름
  const ref = useRef<ScrollViewInstance>(null);
  const [h, setH] = useState(0);       // 한 쪽 높이 = 화면(탭 바 위) 높이
  const [page, setPage] = useState(0);
  const [picked, setPicked] = useState(CURRENT); // 날씨 지역
  const [gi, setGi] = useState(0);               // 가족 쪽에서 보는 그룹
  const [copied, setCopied] = useState(false);
  useEffect(() => { // '복사됨' 2초 뒤 해제. 화면을 떠나면 타이머도 정리
    if (!copied) return;
    const t = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(t);
  }, [copied]);
  const [refreshing, setRefreshing] = useState(false);

  // 1쪽 주인공 = 현재 위치(기준 위치) 지역의 경보·주의보 (위험한 순 첫 번째). 같은 지역의 나머지 재난(정보·등급 없음 포함)은 그 아래 칩
  // 진행 중 재난 (서버). 못 받았으면 '평온'으로 보이지 않게 위치를 모를 때처럼 회색 + 안내
  const disasters = useLoad('active', () => fetchDisasters());
  const known = locState === 'ok';
  const inArea = (d: Disaster) => areas.some(a => sameRegion(d.region, a.region));
  const isHere = (d: Disaster) => known && sameRegion(d.region, location);
  const mine = (d: Disaster) => isHere(d) || inArea(d);
  const active = (disasters.data ?? []).filter(isActive);
  const severe = (d: Disaster) => d.level === '경보' || d.level === '주의보';
  const here = active.filter(isHere).sort((a, b) => rank(a) - rank(b));
  const top = here[0] && severe(here[0]) ? here[0] : undefined;
  const hereRest = here.filter(d => d !== top);
  // 아래 카드 = 관심지역(현재 위치 제외)의 경보·주의보
  const areaAlerts = active.filter(d => !isHere(d) && inArea(d) && severe(d)).sort((a, b) => rank(a) - rank(b));
  const nationwideCalm = !active.some(severe); // 전국 어디에도 경보·주의보 없음
  // 서버가 영향지역을 알려 주지 않은 경보·주의보: 내 지역·관심지역일 수도 있으므로 '평온'으로 단정하지 않음
  // (제목이 다른 지역 이름과 맞아도 실제 발령 지역은 모르므로 모두 포함)
  const unsure = active.filter(d => severe(d) && !d.regionKnown);
  const elsewhere = active.filter(d => !mine(d));

  // 날씨: 기준 위치 / 관심지역 선택 (없으면 조회 실패 — 임의 값을 만들지 않음)
  const others = areas.filter(a => !sameRegion(a.region, location));
  const pickedArea = others.find(a => a.id === picked);
  const base = pickedArea ? picked : CURRENT;
  const region = pickedArea ? pickedArea.name : location;
  const weather = useLoad(pickedArea ? `a:${pickedArea.id}` : known ? (basePos ? `p:${basePos.lat},${basePos.lon}` : `r:${location}`) : null,
    async () => fetchWeather(pickedArea ? { areaId: pickedArea.id } : basePos ?? (await coordsOf(location))));
  const w = weather.data;
  const tip = w?.tip;

  const sheltersRes = useShelters(known ? { pos: basePos, region: location } : null);
  const shelters = sheltersRes.data?.list ?? [];
  const nearest = shelters[0];
  // 아래로 당겨 새로고침: 재난·날씨·대피소·그룹 위치를 다시 받음
  const refresh = () => {
    setRefreshing(true);
    Promise.all([disasters.reload(), weather.reload(), sheltersRes.reload(), member?.operator ? null : reloadGroups()]).finally(() => {
      setRefreshing(false);
      if (focusedRef.current) AccessibilityInfo.announceForAccessibility('재난 정보를 새로고침했어요'); // 다른 화면으로 갔으면 안내하지 않음
    });
  };

  // 2쪽 지도: 기준 위치 → 가장 가까운 대피소 도보 경로 (TMAP). 출발 = GPS 위치 > 장소 검색으로 고른 곳 > 지역 대표 좌표
  // 2쪽을 처음 볼 때 불러옴 (홈을 열 때마다 지도 API 를 부르지 않게). key 가 같으면 다시 부르지 않음
  const [route, setRoute] = useState<{ key: string; value: (Route & { from: LatLon }) | 'error' } | null>(null);
  const [sawShelter, setSawShelter] = useState(false);
  if (page === 1 && !sawShelter) setSawShelter(true);
  const start = sheltersRes.data?.from;
  const routeKey = nearest && start ? `${nearest.id}|${start.lat},${start.lon}` : '';
  const routeNow = route?.key === routeKey ? route.value : 'loading';
  useEffect(() => {
    if (!nearest || !start || !sawShelter || route?.key === routeKey) return;
    let alive = true;
    (async () => ({ ...(await fetchShelterRoute(nearest.id, start)), from: start }))().then(value => alive && setRoute({ key: routeKey, value }), () => alive && setRoute({ key: routeKey, value: 'error' }));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- routeKey 가 출발지·대피소를 모두 담음
  }, [routeKey, sawShelter]);
  const mapH = Math.round(Math.min(380, Math.max(180, h * 0.42))) - 16; // 지도 높이: 쪽 높이의 42% (아래 대피소 정보·버튼이 들어갈 자리는 남김)
  // 세로가 짧은 화면(320×568 등)은 지도 + 아래 대피소 정보가 쪽 높이를 넘어 지도가 정보를 덮음
  // → 지도 묶음 칸의 실제 높이를 재서 그 안에 맞춤 (안내 문구 한두 줄 몫 48 을 뺌). 너무 좁으면 지도는 숨김('지도' 버튼으로 크게 보기)
  const [mapRoom, setMapRoom] = useState<number | null>(null);
  // 아직 못 쟀으면: 짧은 화면은 숨긴 채 시작 (측정이 늦거나 안 와도 글자를 덮지 않게), 그 밖은 기본 높이
  const mapFit = mapRoom === null ? (h < 600 ? 0 : mapH) : Math.min(mapH, Math.floor(mapRoom) - 48);

  const ops = !!member?.operator;
  const pages = ['지금 상황', '대피소', ...(ops ? [] : ['우리 가족'])];
  const go = (i: number) => ref.current?.scrollTo({ y: i * h, animated: true });
  // 쪽 높이가 바뀌면(웹 창 크기 변경·화면 회전) 보던 쪽의 시작에 다시 맞춤 (쪽 사이에 걸쳐 멈추지 않게)
  const pageRef = useRef(0);
  useEffect(() => { pageRef.current = page; }, [page]);
  useEffect(() => { if (h) ref.current?.scrollTo({ y: pageRef.current * h, animated: false }); }, [h]);
  const focused = useIsFocused();
  const focusedRef = useRef(focused);
  useEffect(() => { focusedRef.current = focused; }, [focused]);
  useEffect(() => {
    toFirstPage = () => {
      if (!focusedRef.current) return; // 누르는 순간 홈이 아니었음 = 다른 탭에서 돌아옴
      ref.current?.scrollTo({ y: 0, animated: true }); setPage(0);
    };
    return () => { toFirstPage = () => {}; };
  }, []);
  const nextOf = (name: string) => pages.indexOf(name) + 1;

  // 1쪽 바탕: 현재 위치 경보 = 빨강, 주의보 = 노랑, 없음 = 초록
  // 기준 위치를 모르면(확인 중·실패) 안전으로 판정하지 않음 → 회색 바탕 + 위치 설정 안내
  const unsureHere = !top && known && !!disasters.data && unsure.length > 0;
  const unknownLoc = !top && (locState !== 'ok' || !disasters.data || unsureHere);
  const loadFail = !top && known && !!disasters.error; // 위치는 알지만 재난 정보를 못 받음
  const level = top?.level === '경보' ? '경보' : top ? '주의보' : '안전';
  const field = unknownLoc ? UNKNOWN_FIELD[dark ? 'dark' : 'light'] : NOW_FIELD[dark ? 'dark' : 'light'][level];
  // 1쪽 글자 톤: 라이트 노랑 = 어두운 글자, 라이트 빨강·초록 = 밝은 글자, 다크 = 한 톤 낮춘 밝은 글자
  // 1쪽 글자 톤: 라이트 노랑 = 어두운 글자, 라이트 빨강·초록 = 밝은 글자, 다크 = 한 톤 낮춘 밝은 글자
  const c = dark ? DIM_TONE : level === '주의보' ? LIGHT_TONE : NOW_TONE;
  const p = dark ? stD : level === '주의보' ? stL : stN;

  // 가족 쪽
  const g = groups[Math.min(gi, groups.length - 1)];
  const sorted = g ? [...g.members].sort((a, b) => IMPACT_ORDER[knownImpact(a)] - IMPACT_ORDER[knownImpact(b)]) : [];
  const hits = sorted.filter(m => knownImpact(m) === '영향지역 포함').length;
  const unknown = sorted.filter(m => { const k = knownImpact(m); return k === '판단 불가' || k === '위치정보 미제공'; }).length;
  const totalHits = groups.reduce((n, x) => n + x.members.filter(m => knownImpact(m) === '영향지역 포함').length, 0);
  const copyCode = async () => {
    if (!g) return;
    // 웹은 https 가 아니거나 권한이 없으면 복사가 실패함 → 코드를 직접 보여 줌
    if (!(await Clipboard.setStringAsync(g.code).catch(() => false))) return notify('복사하지 못했어요', `초대 코드: ${g.code}`);
    setCopied(true);
  };

  // 오른쪽은 쪽 표시 자리만큼 더 띄움
  const pad = { paddingTop: insets.top + 8, paddingLeft: gutter, paddingRight: gutter + 10, paddingBottom: 14 };

  const pageViews: Record<string, ReactNode> = {
    // ── 1쪽: 지금 상황 (현재 위치 경보·주의보 우선) ──
    '지금 상황': (
      <Field key="now" h={h} {...field} tone={c} pad={pad}>
        <View style={p.row}>
          <Pressable onPress={() => router.push('/location')} accessibilityRole="button" accessibilityLabel={`기준 위치 ${location}, ${isManual ? '직접 지정' : '현재 위치'}. 바꾸기`}
            style={({ pressed }) => [p.row, { flexShrink: 1, minHeight: 44 }, pressed && p.pressed]}>
            <Icon name={isManual ? 'pin' : 'navigate'} size={20} color={c.ink} />
            <Text style={[p.h3, { flexShrink: 1 }]} numberOfLines={1} maxFontSizeMultiplier={1.3}>{location}</Text>
            <Text style={p.pill} maxFontSizeMultiplier={1.3}>{isManual ? '직접 지정' : '현재 위치'}</Text>
          </Pressable>
          {w && <View style={[p.row, { marginLeft: 'auto', gap: 4 }]} accessible accessibilityLabel={`${region} ${w.text}${w.temp !== null ? ` ${w.temp}도` : ''}`}>
            <Icon name={WEATHER_ICON[w.code] ?? 'partly-sunny'} size={18} color={c.ink} />
            <Text style={[p.b, { fontVariant: ['tabular-nums'] }]} maxFontSizeMultiplier={1.3}>{w.text}{w.temp !== null ? ` ${w.temp}°` : ''}</Text>
          </View>}
        </View>

        {/* 날씨 지역 선택: 기준 위치 + 관심지역 (없으면 관심지역 추가) */}
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }} style={{ marginTop: 6, flexGrow: 0 }}>
          <DarkChip text={isManual ? '지정 위치' : '현재 위치'} on={base === CURRENT} onPress={() => setPicked(CURRENT)} />
          {others.map(a => <DarkChip key={a.id} text={a.name} on={base === a.id} onPress={() => setPicked(a.id)} />)}
          {others.length === 0 && <DarkChip text="관심지역 추가" icon="add" on={false} onPress={() => router.push('/areas')} />}
        </ScrollView>
        {w ? (
          <View style={{ marginTop: 6 }}>
            {!!tip && <Text style={p.body} lineBreakStrategyIOS="hangul-word">{tip}</Text>}
            <Text style={p.small}>날씨 · {region} · {w.at} 기준{w.stale ? ' · 최신 자료 아님' : ''}</Text>
          </View>
        ) : (
          <View style={[p.row, { marginTop: 6 }]}>
            <Icon name="cloud-offline-outline" size={18} color={c.soft} />
            <Text style={[p.small, { flex: 1 }]}>{base === CURRENT && !known ? '현재 위치를 확인하면 날씨를 보여 드려요.' : weather.loading ? '날씨를 불러오는 중…' : `${region}의 날씨 정보를 확인할 수 없어요.`}</Text>
          </View>
        )}

        <View style={{ flex: 1 }} />

        <View style={[p.row, { alignItems: 'flex-end', gap: 12 }]}>
          <View style={{ flex: 1 }}>
            {top ? (
              <Pressable onPress={() => router.push(`/disasters/${top.id}`)} accessibilityRole="button"
                accessibilityLabel={`현재 위치 ${top.region} ${top.type}${top.level} ${top.status}. 자세히 보기`} style={({ pressed }) => pressed && p.pressed}>
                <View style={[p.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: c.ink, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name={LEVEL_ICON[top.level!]} size={16} color={field.bg} />
                  <Text style={{ color: field.bg, fontWeight: '900', fontSize: 14 }}>현재 위치 {top.level} · {top.status}</Text>
                </View>
                <Text style={p.hero} maxFontSizeMultiplier={1.25} adjustsFontSizeToFit numberOfLines={1}>{top.type}{top.level}</Text>
                <Text style={[p.b, { marginTop: 4 }]}>{top.region} · {top.occurredAt} 발령</Text>
                <Text style={[p.body, { marginTop: 6 }]} numberOfLines={3} lineBreakStrategyIOS="hangul-word">
                  {top.title || '눌러서 공식 원문과 AI 요약을 확인하세요.'}
                </Text>
                <View style={[p.row, { gap: 4, marginTop: 6 }]}>
                  <Icon name="sparkles-outline" size={14} color={c.soft} />
                  <Text style={[p.small, { flex: 1 }]}>
                    {top.updatedAt} 갱신 · 자세히 보면 공식 원문
                  </Text>
                </View>
                {top.stale && (
                  <View style={[p.row, { gap: 4, marginTop: 4 }]}>
                    <Icon name="cloud-offline-outline" size={14} color={c.ink} />
                    <Text style={[p.small, { color: c.ink, fontWeight: '700', flex: 1 }]}>최신 수집 실패 · 마지막 정상 갱신 {top.updatedAt}</Text>
                  </View>
                )}
              </Pressable>
            ) : unknownLoc ? (
              unsureHere ? (
              <Pressable onPress={() => router.push('/disasters')} accessibilityRole="button"
                accessibilityLabel={`지역을 확인하지 못한 경보·주의보 ${unsure.length}건. 재난 목록에서 보기`} style={({ pressed }) => pressed && p.pressed}>
                <View style={[p.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: c.ink, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name="help-circle" size={16} color={field.bg} />
                  <Text style={{ color: field.bg, fontWeight: '900', fontSize: 14 }}>지역 확인 필요 · {unsure.length}건</Text>
                </View>
                <Text style={[p.hero, { fontSize: 40, lineHeight: 46 }]} maxFontSizeMultiplier={1.25}>{'지역을 알 수 없는\n경보가 있어요'}</Text>
                <Text style={[p.body, { marginTop: 6 }]} lineBreakStrategyIOS="hangul-word">
                  {`${unsure.map(d => `${d.type}${d.level ?? ''}`).slice(0, 3).join(', ')}${unsure.length > 3 ? ' 외' : ''} — 발령 지역을 확인하지 못해 ${location}이 포함되는지 판단하지 않았어요. 눌러서 원문을 확인하세요.`}
                </Text>
              </Pressable>
              ) : loadFail || (known && !disasters.data) ? (
              <Pressable onPress={() => disasters.reload()} accessibilityRole="button" disabled={!loadFail}
                accessibilityLabel={loadFail ? '재난 정보를 불러오지 못했어요. 다시 시도' : '재난 정보를 불러오는 중이에요'} style={({ pressed }) => pressed && p.pressed}>
                <View style={[p.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: c.ink, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name={loadFail ? 'cloud-offline' : 'sync'} size={16} color={field.bg} />
                  <Text style={{ color: field.bg, fontWeight: '900', fontSize: 14 }}>{loadFail ? '재난 정보 확인 불가' : '재난 정보 확인 중'}</Text>
                </View>
                <Text style={[p.hero, { fontSize: 40, lineHeight: 46 }]} maxFontSizeMultiplier={1.25}>
                  {loadFail ? '재난 정보를\n받지 못했어요' : '재난 정보를\n확인하고 있어요'}
                </Text>
                <Text style={[p.body, { marginTop: 6 }]} lineBreakStrategyIOS="hangul-word">
                  {loadFail ? '눌러서 다시 시도하세요. 확인되기 전까지는 안전 여부를 판단하지 않아요. 공식 재난문자와 방송 안내를 함께 확인하세요.' : '잠시만 기다려 주세요.'}
                </Text>
              </Pressable>
              ) : (
              <Pressable onPress={() => router.push('/location')} accessibilityRole="button" disabled={locState === 'loading'}
                accessibilityLabel={locState === 'loading' ? '현재 위치를 확인하는 중이에요' : '현재 위치를 확인하지 못했어요. 기준 위치 직접 지정하기'}
                style={({ pressed }) => pressed && p.pressed}>
                <View style={[p.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: c.ink, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name={locState === 'loading' ? 'locate-outline' : 'alert-circle'} size={16} color={field.bg} />
                  <Text style={{ color: field.bg, fontWeight: '900', fontSize: 14 }}>{locState === 'loading' ? '위치 확인 중' : '위치 확인 불가'}</Text>
                </View>
                <Text style={[p.hero, { fontSize: 40, lineHeight: 46 }]} maxFontSizeMultiplier={1.25}>
                  {locState === 'loading' ? '현재 위치를\n확인하고 있어요' : '현재 위치를\n알 수 없어요'}
                </Text>
                <Text style={[p.body, { marginTop: 6 }]} lineBreakStrategyIOS="hangul-word">
                  {locState === 'loading'
                    ? '위치를 확인하면 이곳의 경보·주의보를 알려 드려요.'
                    : '위치 권한을 확인하거나, 눌러서 기준 위치를 직접 지정하세요. 위치를 모르는 동안에는 안전 여부를 판단하지 않아요.'}
                </Text>
              </Pressable>
              )
            ) : (
              <View accessible>
                <View style={[p.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: c.ink, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name="checkmark-circle" size={16} color={field.bg} />
                  <Text style={{ color: field.bg, fontWeight: '900', fontSize: 14 }}>경보·주의보 없음</Text>
                </View>
                <Text style={[p.hero, { fontSize: 44, lineHeight: 50 }]} maxFontSizeMultiplier={1.25}>지금 이곳은{'\n'}평온해요</Text>
                <Text style={[p.body, { marginTop: 6 }]}>{location}에 발령된 경보·주의보가 없어요.</Text>
              </View>
            )}
            {/* 현재 위치의 나머지 재난 (위험한 순, 정보·등급 없음 포함) */}
            {hereRest.length > 0 && (
              <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }} style={{ marginTop: 10, flexGrow: 0 }}>
                {hereRest.map(d => (
                  <DarkChip key={d.id} text={`${d.type}${d.level ?? ' · 등급 없음'}`} icon={d.level ? LEVEL_ICON[d.level] : TYPE_ICON[d.type]} on={false}
                    label={`${d.region} ${d.type}${d.level ?? ''} ${d.status}. 자세히 보기`} onPress={() => router.push(`/disasters/${d.id}`)} />
                ))}
              </ScrollView>
            )}
          </View>
          {/* 행동 버튼 줄은 화면 오른쪽 끝 가까이 (쪽 표시는 더 위에 있어 겹치지 않음) */}
          <View style={{ gap: 12, alignItems: 'center', marginRight: -(gutter + 4) }}>
            {/* 대피소·경로는 2쪽에 있어 1쪽에서는 뺌 */}
            <RailBtn icon="chatbubbles-outline" label="행동요령" a11y="행동요령 물어보기" onPress={() => router.push('/chat')} />
            {top
              ? <RailBtn icon="document-text-outline" label="원문" a11y="재난 원문 보기" onPress={() => router.push(`/disasters/${top.id}`)} />
              : <RailBtn icon="list" label="재난 목록" a11y="재난 전체 목록" onPress={() => router.push('/disasters')} />}
          </View>
        </View>

        {/* 아래 카드: 관심지역 경보·주의보 (없으면 안심 문구) */}
        <NextCard>
          <View style={[p.row, { marginBottom: 4 }]}>
            <Icon name="star" size={14} color={c.soft} />
            <Text style={[p.small, { flex: 1, fontWeight: '700' }]} accessibilityRole="header">관심지역 경보·주의보</Text>
            {others.length > 0 && <Text style={p.small}>관심지역 {others.length}곳</Text>}
          </View>
          {areaAlerts.slice(0, 2).map((d, i) => (
            <Pressable key={d.id} onPress={() => router.push(`/disasters/${d.id}`)} accessibilityRole="button"
              accessibilityLabel={`관심지역 ${d.region} ${d.type}${d.level} ${d.status}${d.stale ? ', 최신 수집 실패' : ''}. 자세히 보기`}
              style={({ pressed }) => [p.row, { minHeight: 48, gap: 10 }, i > 0 && p.divTop, pressed && p.pressed]}>
              <Text style={[p.lv, { backgroundColor: TAG[d.level!].bg, color: TAG[d.level!].fg }]}>{d.type} {d.level}</Text>
              <View style={{ flex: 1 }}>
                <Text style={p.b} numberOfLines={1}>{areas.find(a => sameRegion(d.region, a.region))?.name ?? d.region}</Text>
                <Text style={p.small} numberOfLines={1}>{d.stale ? '수집 지연 · ' : ''}{d.status} · 갱신 {d.updatedAt}</Text>
              </View>
              <Icon name="chevron-forward" size={18} color={c.soft} />
            </Pressable>
          ))}
          {areaAlerts.length > 2 && (
            <Pressable onPress={() => router.push('/disasters')} accessibilityRole="button" style={({ pressed }) => [p.row, p.divTop, { minHeight: 44, justifyContent: 'center' }, pressed && p.pressed]}>
              <Text style={p.b}>외 {areaAlerts.length - 2}건 더 보기</Text>
            </Pressable>
          )}
          {areaAlerts.length === 0 && (others.length === 0 ? (
            <Pressable onPress={() => router.push('/areas')} accessibilityRole="button" style={({ pressed }) => [p.row, { minHeight: 48 }, pressed && p.pressed]}>
              <Icon name="add-circle-outline" size={22} color={c.ink} />
              <Text style={[p.body, { flex: 1 }]}>관심지역을 추가하면 경보·주의보를 여기서 함께 볼 수 있어요</Text>
            </Pressable>
          ) : (
            <View style={[p.row, { minHeight: 48 }]} accessible>
              <Icon name="shield-checkmark" size={24} color={c.ink} />
              <View style={{ flex: 1 }}>
                {/* 전국에 경보·주의보가 하나도 없을 때만 '전국' 문구 (다른 지역에 있으면 관심지역 기준으로만 말함) */}
                <Text style={p.b}>{nationwideCalm ? '지금 전국에 경보·주의보가 없어요' : unsure.length ? `지역을 확인하지 못한 경보 ${unsure.length}건이 있어요` : '관심지역은 모두 평온해요'}</Text>
                <Text style={p.small} numberOfLines={1}>{others.map(a => a.name).join(' · ')} · 발령된 경보·주의보 없음</Text>
              </View>
            </View>
          ))}
          <View style={[p.row, { flexWrap: 'wrap', gap: 6, marginTop: 8 }]}>
            {!ops && totalHits > 0 && <SmallTag text={`가족 ${totalHits}명 영향지역`} hit onPress={() => go(nextOf('대피소'))} />}
            <SmallTag text={`다른 지역 ${elsewhere.length}건`} onPress={() => router.push('/disasters')} />
          </View>
        </NextCard>
      </Field>
    ),

    // ── 2쪽: 대피소 ──
    '대피소': (
      <Field key="shelter" h={h} {...FIELD.shelter} pad={pad}>
        <View style={st.row}>
          <Text style={st.h3}>대피소</Text>
          <Text style={[st.small, { flex: 1 }]} numberOfLines={1}>{location} 기준</Text>
          {top && <Text style={[st.tag, { backgroundColor: field.bg, borderColor: field.bg, color: INK }]}>{top.type}{top.level ?? ''} {top.status}</Text>}
        </View>
        {nearest ? (
          <>
            {/* 경로 지도 (누르면 크게 보기). 경로를 못 찾으면 출발·대피소 위치만
                좌우 여백 = 화면 여백 + 8 로 같게 (오른쪽 쪽 표시 막대와 겹치지 않게) */}
            {/* 지도 묶음을 위 제목 줄과 아래 대피소 정보 사이 빈 공간의 가운데에 둠 → 위·아래 여백이 같음 */}
            {/* key: 웹(react-native-web)은 이미 있던 View 를 재사용하면서 onLayout 을 새로 붙이면 크기 감시를 시작하지 않음
                → 대피소 자료가 온 뒤 이 칸을 항상 새로 만들어 크기를 재게 함 */}
            <View key="map-room" style={{ flex: 1, minHeight: 0, justifyContent: 'center' }} onLayout={e => setMapRoom(e.nativeEvent.layout.height)}>
            {mapFit >= 90 && <View style={{ marginLeft: 8, marginRight: -2 }}>
              {routeNow === 'loading' ? (
                <View style={[st.row, { height: mapFit, borderRadius: 12, justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.25)' }]}>
                  <ActivityIndicator color={INK} />
                  <Text style={st.small}>대피소까지 경로를 찾는 중…</Text>
                </View>
              ) : routeNow === 'error' ? (
                <>
                  <TmapView height={mapFit} markers={[{ lat: nearest.lat, lon: nearest.lon, title: `1. ${nearest.name}`, label: '1' }, ...(myPos ? [{ ...myPos, title: '내 위치', me: true }] : [])]}
                    label={`${nearest.name} 위치 지도`} />
                  <Text style={[st.small, { marginTop: 6 }]}>경로를 불러오지 못해 위치만 보여요. 경로 버튼에서 다시 찾을 수 있어요.</Text>
                </>
              ) : (
                <>
                  <TmapView height={mapFit} path={routeNow.path} label={`${location}에서 ${nearest.name}까지 도보 경로 지도`}
                    markers={[{ ...routeNow.from, title: '출발', me: true }, { lat: nearest.lat, lon: nearest.lon, title: `1. ${nearest.name}`, label: '1' }]} />
                  <View style={[st.row, { marginTop: 6, gap: 6 }]}>
                    <Icon name="walk" size={16} color={SOFT} />
                    <Text style={st.small}>도보 {(routeNow.meters / 1000).toFixed(1)}km · 약 {Math.max(1, Math.round(routeNow.seconds / 60))}분 · 지도 서비스 추천 경로</Text>
                  </View>
                </>
              )}
            </View>}
            </View>
            <View style={[st.row, { alignItems: 'flex-end', gap: 12 }]}>
              <View style={{ flex: 1 }} accessible accessibilityLabel={`가장 가까운 대피소 ${nearest.name}, 직선 ${nearest.distance}킬로미터, ${nearest.addr}`}>
                <View style={[st.row, { gap: 4, alignSelf: 'flex-start', backgroundColor: GO, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
                  <Icon name="shield-checkmark" size={16} color="#04140C" />
                  <Text style={{ color: '#04140C', fontWeight: '900', fontSize: 14 }}>가장 가까운 대피소</Text>
                </View>
                <Text style={[st.hero, { fontSize: 32, lineHeight: 38 }]} maxFontSizeMultiplier={1.3} lineBreakStrategyIOS="hangul-word">{nearest.name}</Text>
                <Text style={st.bigKm} maxFontSizeMultiplier={1.25}>{nearest.distance}<Text style={[st.h3, { fontSize: 24 }]}>km</Text></Text>
                <Text style={st.body}>{nearest.type ?? '유형 정보 없음'} · {nearest.capacity ? `수용 ${nearest.capacity.toLocaleString()}명` : '수용인원 정보 없음'} · 직선거리</Text>
                <Text style={st.small}>{nearest.addr}</Text>
              </View>
              <View style={{ gap: 12, alignItems: 'center', marginRight: -(gutter + 4) }}>
                <RailBtn icon="navigate" label="경로" tone="go" a11y={`${nearest.name} 경로 안내`} onPress={() => router.push(`/route/${nearest.id}`)} />
                <RailBtn icon="map-outline" label="지도" a11y="대피소 지도 보기" onPress={() => router.navigate('/shelter')} />
              </View>
            </View>
            <NextCard>
              {shelters.slice(1, 3).map((x, i) => (
                <Pressable key={x.id} onPress={() => router.push(`/route/${x.id}`)} accessibilityRole="button" accessibilityLabel={`${i + 2}번 ${x.name}, ${x.distance}킬로미터. 경로 안내`}
                  style={({ pressed }) => [st.row, { minHeight: 48, gap: 10 }, i > 0 && st.divTop, pressed && st.pressed]}>
                  <Text style={st.no}>{i + 2}</Text>
                  <View style={{ flex: 1 }}><Text style={st.b} numberOfLines={1}>{x.name}</Text><Text style={st.small} numberOfLines={1}>{x.type}</Text></View>
                  <Text style={[st.b, { fontVariant: ['tabular-nums'] }]}>{x.distance}km</Text>
                  <Icon name="navigate" size={16} color={SOFT} />
                </Pressable>
              ))}
              <Pressable onPress={() => router.navigate('/shelter')} accessibilityRole="button"
                style={({ pressed }) => [st.row, { minHeight: 44, justifyContent: 'center' }, shelters.length > 1 && st.divTop, pressed && st.pressed]}>
                <Text style={[st.b, { color: '#8BE0B6' }]}>{shelters.length > 3 ? `외 ${shelters.length - 3}곳 · ` : ''}대피소 탭에서 지도로 보기</Text>
                <Icon name="chevron-forward" size={16} color="#8BE0B6" />
              </Pressable>
            </NextCard>
          </>
        ) : (
          <>
            <View style={{ flex: 1 }} />
            {known && (sheltersRes.loading || sheltersRes.error) ? (
              // 찾는 중·실패를 '대피소 없음'으로 보이지 않게
              <>
                <Text style={[st.hero, { fontSize: 36, lineHeight: 42 }]}>{sheltersRes.loading ? '가까운 대피소를\n찾고 있어요' : '대피소 정보를\n받지 못했어요'}</Text>
                {!!sheltersRes.error && <Text style={[st.body, { marginTop: 6 }]}>{sheltersRes.error}</Text>}
                {!!sheltersRes.error && <LightBtn title="다시 시도" icon="refresh" onPress={() => sheltersRes.reload()} />}
              </>
            ) : locState !== 'ok' ? (
              // 위치를 모르면 '대피소 없음'이 아니라 '위치 필요' (없다고 단정하지 않음)
              <>
                <Text style={[st.hero, { fontSize: 36, lineHeight: 42 }]}>현재 위치를{'\n'}{locState === 'loading' ? '확인하고 있어요' : '알 수 없어요'}</Text>
                <Text style={[st.body, { marginTop: 6 }]}>위치를 확인하면 가장 가까운 대피소를 보여 드려요.</Text>
                {locState === 'fail' && <LightBtn title="기준 위치 직접 지정" icon="pin" onPress={() => router.push('/location')} />}
              </>
            ) : (
              <>
                <Text style={[st.hero, { fontSize: 36, lineHeight: 42 }]}>반경 {MAX_RADIUS}km 안에{'\n'}등록된 대피소가 없어요</Text>
                <Text style={[st.body, { marginTop: 6 }]}>대피소 탭에서 다른 기준 위치로 찾아보세요.</Text>
                <LightBtn title="대피소 탭에서 찾기" icon="shield-outline" onPress={() => router.navigate('/shelter')} />
              </>
            )}
            <View style={{ height: 24 }} />
          </>
        )}
      </Field>
    ),

    // ── 3쪽: 우리 가족 (안심그룹) ──
    '우리 가족': (
      <Field key="family" h={h} {...FIELD.family} pad={pad}>
        <View style={st.row}>
          <Text style={st.h3}>{g ? g.name : '안심그룹'}</Text>
          <Text style={[st.small, { flex: 1 }]}>{g ? `${g.category} · ${g.members.length}명` : ''}</Text>
        </View>
        {groups.length > 1 && (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }} style={{ marginTop: 6, flexGrow: 0 }}>
            {groups.map((x, i) => {
              const n = x.members.filter(m => knownImpact(m) === '영향지역 포함').length;
              return <DarkChip key={x.id} text={`${x.name}${n ? ` · ${n}명 영향` : ''}`} on={x === g} onPress={() => setGi(i)} />;
            })}
          </ScrollView>
        )}
        {/* 그룹을 받는 중·실패: '그룹 없음'이나 '모두 안전'으로 보이지 않게 */}
        {groupsState === 'error' && (
          <Pressable onPress={() => reloadGroups()} accessibilityRole="button" style={({ pressed }) => [st.row, { marginTop: 10, gap: 6 }, pressed && st.pressed]}>
            <Icon name="cloud-offline-outline" size={16} color={HIT} />
            <Text style={[st.b, { color: HIT, flex: 1 }]}>{g ? '최신 위치·영향상태를 받지 못했어요. 아래는 마지막으로 받은 정보예요.' : '안심그룹 정보를 받지 못했어요.'} 눌러서 다시 시도</Text>
          </Pressable>
        )}
        {!g && groupsState !== 'ok' ? (
          <>
            <View style={{ flex: 1 }} />
            <Text style={[st.hero, { fontSize: 36, lineHeight: 42 }]}>{groupsState === 'loading' ? '안심그룹을\n불러오고 있어요' : '안심그룹을\n확인할 수 없어요'}</Text>
            <View style={{ height: 24 }} />
          </>
        ) : !g ? (
          <>
            <View style={{ flex: 1 }} />
            <Text style={[st.hero, { fontSize: 40, lineHeight: 46 }]}>안심그룹이{'\n'}아직 없어요</Text>
            <Text style={[st.body, { marginTop: 6 }]}>가족·친구와 그룹을 만들면 재난 때 서로가 영향지역 안에 있는지 볼 수 있어요.</Text>
            <LightBtn title="안심그룹 만들기" icon="people" onPress={() => router.navigate('/group')} />
          </>
        ) : (
          <>
            <Text style={[st.hero, { fontSize: 40, lineHeight: 46, marginTop: 16 }]} maxFontSizeMultiplier={1.25} accessibilityRole="header">
              {hits ? <><Text style={{ color: HIT }}>{hits}명</Text>이{'\n'}영향지역 안</> : <>영향지역 안에{'\n'}있는 사람이 없어요</>}
            </Text>
            <Text style={[st.body, { marginTop: 6 }]}>{unknown ? `${unknown}명은 위치를 확인할 수 없어요` : '모든 구성원의 위치를 확인했어요'}</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 14 }}>
              {sorted.slice(0, TILE_MAX).map(m => {
                const k = knownImpact(m);
                const old = (ageMin(m.updatedAt) ?? 0) > STALE_MIN;
                const hit = k === '영향지역 포함';
                const vague = k === '판단 불가' || k === '위치정보 미제공';
                return (
                  <Pressable key={m.id} onPress={() => router.push(`/groups/${g.id}`)} accessibilityRole="button"
                    accessibilityLabel={`${m.name} ${k}${m.sharing ? `, ${freshness(m.updatedAt)}` : ', 위치 공유 안 함'}. 그룹 자세히`}
                    style={({ pressed }) => [st.tile, hit && { backgroundColor: 'rgba(214,59,48,0.22)', borderColor: 'rgba(255,156,146,0.6)' },
                      vague && { borderStyle: 'dashed' }, pressed && st.pressed]}>
                    <View style={st.row}>
                      <Text style={[st.h3, { flex: 1 }]} numberOfLines={1}>{m.name}</Text>
                      {m.admin && <Icon name="ribbon" size={16} color="#F2C230" accessibilityLabel="관리자" />}
                    </View>
                    <Text style={st.small} numberOfLines={1}>{m.sharing ? freshness(m.updatedAt) : '위치 공유 안 함'}</Text>
                    <View style={[st.row, { gap: 4, marginTop: 2 }]}>
                      <Icon name={IMPACT_ICON[k]} size={14} color={hit ? HIT : SOFT} />
                      <Text style={[st.b, { fontSize: 14, color: hit ? HIT : SOFT, flexShrink: 1 }]} numberOfLines={1}>{old && m.sharing ? '오래된 위치 · 확인 필요' : k}</Text>
                    </View>
                  </Pressable>
                );
              })}
            </View>
            <View style={{ flex: 1 }} />
            <View style={[st.row, { gap: 8 }]}>
              <View style={{ flex: 1 }}>
                <LightBtn title={sorted.length > TILE_MAX ? `그룹 자세히 (외 ${sorted.length - TILE_MAX}명)` : '그룹 자세히'} icon="people" onPress={() => router.push(`/groups/${g.id}`)} />
              </View>
              <View style={{ flex: 1 }}>
                <LightBtn outline title={copied ? '복사됨' : '초대 코드 복사'} icon={copied ? 'checkmark' : 'copy-outline'} onPress={copyCode} a11y={`초대 코드 ${g.code} 복사`} />
              </View>
            </View>
          </>
        )}
      </Field>
    ),

  };

  return (
    <View style={{ flex: 1, backgroundColor: FIELD.family.bg }} onLayout={e => setH(e.nativeEvent.layout.height)}>
      {h > 0 && (
        <ScrollView ref={ref} pagingEnabled snapToInterval={h} decelerationRate="fast" disableIntervalMomentum showsVerticalScrollIndicator={false}
          scrollEventThrottle={32} onScroll={e => setPage(Math.round(e.nativeEvent.contentOffset.y / h))}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={INK} colors={[GO]} title="재난 정보 새로고침" titleColor={INK} />}>
          {pages.map(p => pageViews[p])}
        </ScrollView>
      )}
      {/* 쪽 표시 (누르면 그 쪽으로) */}
      <View style={{ position: 'absolute', right: 4, top: insets.top + 132, gap: 2 }}>
        {pages.map((p, i) => (
          <Pressable key={p} onPress={() => go(i)} hitSlop={{ left: 12, right: 6 }} accessibilityRole="button" accessibilityLabel={`${i + 1}쪽 ${p}로 이동`}
            accessibilityState={{ selected: i === page }} style={{ paddingVertical: 3, paddingHorizontal: 6 }}>
            <View style={{ width: 4, height: i === page ? 28 : 14, borderRadius: 2, backgroundColor: i === page ? (page === 0 ? c.ink : INK) : page === 0 ? c.faint : 'rgba(246,241,236,0.35)' }} />
          </Pressable>
        ))}
      </View>
    </View>
  );
}

// 한 쪽: 화면 높이만큼의 색면 + 오른쪽 위 번짐
function Field({ h, bg, glow, tone = DARK_TONE, pad, children }: { h: number; bg: string; glow: string; tone?: Tone; pad: object; children: ReactNode }) {
  return (
    <ToneCtx.Provider value={{ ...tone, bg }}>
      <View style={[{ height: h, backgroundColor: bg, overflow: 'hidden' }, pad]}>
        <View style={{ position: 'absolute', top: -180, right: -160, width: 440, height: 440, borderRadius: 220, backgroundColor: glow, opacity: 0.55 }} />
        {children}
      </View>
    </ToneCtx.Provider>
  );
}

// 오른쪽 행동 버튼 줄 (원 + 아래 글자). go = 대피 녹색
function RailBtn({ icon, label, tone, a11y, onPress }: { icon: IconName; label: string; tone?: 'go'; a11y: string; onPress: () => void }) {
  const t = useContext(ToneCtx);
  const size = tone === 'go' ? 62 : 54;
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={a11y} style={({ pressed }) => [{ alignItems: 'center', gap: 4, width: 72 }, pressed && st.pressed]}>
      {/* 경로 = 녹색 원(바탕이 초록이어도 보이게 글자색 테두리) */}
      <View style={{ width: size, height: size, borderRadius: size / 2, justifyContent: 'center', alignItems: 'center', borderWidth: tone === 'go' ? 2 : 1.5,
        ...(tone === 'go' ? { backgroundColor: GO, borderColor: t.ink } : { backgroundColor: t.btn, borderColor: t.faint }) }}>
        <Icon name={icon} size={tone === 'go' ? 28 : 24} color={tone === 'go' ? '#04140C' : t.ink} />
      </View>
      <Text style={[st.small, { color: t.ink, fontWeight: '700', textAlign: 'center' }]} maxFontSizeMultiplier={1.3}>{label}</Text>
    </Pressable>
  );
}

// 아래 카드 (다음 쪽 미리보기). onPress 가 있으면 눌러서 다음 쪽으로
function NextCard({ children, onPress, label }: { children: ReactNode; onPress?: () => void; label?: string }) {
  const t = useContext(ToneCtx);
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessible={!!onPress} accessibilityRole={onPress ? 'button' : undefined} accessibilityLabel={label}
      style={({ pressed }) => [st.next, { marginTop: 14, backgroundColor: t.card, borderColor: t.line }, pressed && st.pressed]}>
      {!!onPress && <View style={{ alignSelf: 'center', width: 36, height: 4, borderRadius: 2, backgroundColor: t.faint, marginBottom: 10 }} />}
      {children}
    </Pressable>
  );
}

function DarkChip({ text, on, icon, label, onPress }: { text: string; on: boolean; icon?: IconName; label?: string; onPress: () => void }) {
  const t = useContext(ToneCtx);
  const fg = on ? t.bg : t.ink; // 선택 = 글자색 바탕 + 쪽 바탕색 글자
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityState={{ selected: on }} accessibilityLabel={label}
      style={({ pressed }) => [st.row, { gap: 4, minHeight: 40, paddingHorizontal: 12, borderRadius: 20, borderWidth: 1 },
        on ? { backgroundColor: t.ink, borderColor: t.ink } : { borderColor: t.faint }, pressed && st.pressed]}>
      {icon && <Icon name={icon} size={16} color={fg} />}
      <Text style={{ color: fg, fontWeight: '700', fontSize: 14 }} maxFontSizeMultiplier={1.4}>{text}</Text>
    </Pressable>
  );
}

// 아래 카드의 작은 바로가기 (가족 영향 · 다른 지역 수)
function SmallTag({ text, hit, onPress }: { text: string; hit?: boolean; onPress: () => void }) {
  const t = useContext(ToneCtx);
  const color = hit && t.ink !== LIGHT_TONE.ink ? HIT : t.ink; // 어두운 카드 위에서만 분홍 강조 (노란 쪽 밝은 카드 위는 글자색)
  return (
    <Pressable onPress={onPress} accessibilityRole="button" hitSlop={6}
      style={({ pressed }) => [{ borderWidth: 1, borderColor: hit ? color : t.faint, borderRadius: 12, paddingHorizontal: 9, paddingVertical: 3 }, pressed && st.pressed]}>
      <Text style={{ color, fontSize: 13, fontWeight: '700' }}>{text}</Text>
    </Pressable>
  );
}

function LightBtn({ title, icon, outline, a11y, onPress }: { title: string; icon: IconName; outline?: boolean; a11y?: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={a11y}
      style={({ pressed }) => [st.row, { minHeight: 50, borderRadius: 12, justifyContent: 'center', marginTop: 14, paddingHorizontal: 10 },
        outline ? { borderWidth: 1.5, borderColor: 'rgba(246,241,236,0.55)' } : { backgroundColor: INK }, pressed && st.pressed]}>
      <Icon name={icon} size={18} color={outline ? INK : '#0D1428'} />
      <Text style={{ color: outline ? INK : '#0D1428', fontWeight: '800', fontSize: 15, flexShrink: 1, textAlign: 'center' }}>{title}</Text>
    </Pressable>
  );
}

const mk = (t: Tone) => StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  pressed: { opacity: 0.7 },
  h3: { color: t.ink, fontSize: 19, lineHeight: 26, fontWeight: '900' },
  hero: { color: t.ink, fontSize: 56, lineHeight: 62, fontWeight: '900', letterSpacing: -1, marginTop: 10 },
  bigKm: { color: t.ink, fontSize: 52, lineHeight: 58, fontWeight: '900', fontVariant: ['tabular-nums'], marginTop: 2 },
  body: { color: t.ink, fontSize: 16, lineHeight: 24 },
  b: { color: t.ink, fontSize: 16, lineHeight: 22, fontWeight: '800' },
  small: { color: t.soft, fontSize: 13, lineHeight: 18 },
  km: { color: t.ink, fontSize: 26, lineHeight: 30, fontWeight: '900', fontVariant: ['tabular-nums'] },
  pill: { color: t.soft, fontSize: 13, borderWidth: 1, borderColor: t.faint, borderRadius: 12, paddingHorizontal: 8, paddingVertical: 1, overflow: 'hidden' },
  tag: { color: t.soft, fontSize: 13, fontWeight: '700', borderWidth: 1, borderColor: t.faint, borderRadius: 12, paddingHorizontal: 9, paddingVertical: 2, overflow: 'hidden' },
  lv: { fontSize: 13, fontWeight: '900', borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2, overflow: 'hidden' },
  ic: { width: 42, height: 42, borderRadius: 12, justifyContent: 'center', alignItems: 'center' },
  no: { color: t.ink, width: 26, height: 26, borderRadius: 6, backgroundColor: 'rgba(241,245,242,0.14)', textAlign: 'center', lineHeight: 26, fontWeight: '900', fontSize: 13, overflow: 'hidden' },
  next: { backgroundColor: 'rgba(8,8,10,0.55)', borderWidth: 1, borderColor: 'rgba(246,241,236,0.16)', borderRadius: 18, paddingHorizontal: 16, paddingVertical: 10 },
  divTop: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.line },
  tile: { width: '47%', flexGrow: 1, minHeight: 96, borderRadius: 16, padding: 12, gap: 2, backgroundColor: 'rgba(241,242,246,0.07)', borderWidth: 1, borderColor: 'rgba(241,242,246,0.16)' },
});
const st = mk(DARK_TONE);  // 어두운 바탕 쪽
const stL = mk(LIGHT_TONE); // 라이트 노란 바탕(주의보) 1쪽
const stN = mk(NOW_TONE);   // 라이트 빨강·초록 1쪽
const stD = mk(DIM_TONE);   // 다크 1쪽
