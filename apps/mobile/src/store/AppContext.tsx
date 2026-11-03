// 앱 전역 상태 (화면 이동은 expo-router 가 담당)
import * as Location from 'expo-location';
import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api, endSession, logoutSession, restoreSession, setOnSessionExpired } from '../data/api';
import { GPS_LOCATION, INIT_GROUPS, Level, ME, MemberInfo, Provider } from '../data/mock';
import { getItem, removeItem, setItem } from '../data/storage';
import { LatLon, Place, regionOf } from '../data/tmap';

// FS-LOC-001·003: 위치 동의·기준 위치를 기기에 저장 → 다시 열어도 그대로. owner 로 회원을 구분 (다른 회원이면 다시 물음)
const LOC_KEY = 'eodiisseo.location';
type LocPrefs = { owner: string; locConsent: boolean; manualLoc: boolean; picked: string; place: Place | null };
const NO_PREFS = { locConsent: false, manualLoc: false, picked: GPS_LOCATION, place: null };
async function loadLocPrefs(owner: string): Promise<LocPrefs | null> {
  const raw = await getItem(LOC_KEY).catch(() => null);
  try {
    const prefs = raw ? (JSON.parse(raw) as LocPrefs) : null;
    return prefs?.owner === owner ? prefs : null;
  } catch {
    return null;
  }
}

export type ThemeMode = 'system' | 'light' | 'dark';

const INIT_AREAS = ['서울 강남구', '경기 수원시'];
// FS-NOTI-001: areas = 알림 대상 관심지역, minLevel = 최소 심각도, night = 22시 이후 수신
const INIT_NOTI = { on: true, areas: INIT_AREAS, minLevel: '주의보' as Level, night: false };

function useAppState() {
  const [member, setMember] = useState<MemberInfo | null>(null); // null = 비로그인 상태 (라우터가 로그인 화면으로 보냄)
  const [needConsent, setNeedConsent] = useState(false);           // 로그인 직후 위치 동의 화면을 보여줄지
  const [provider, setProvider] = useState<Provider>('Google');   // 로그인에 사용한 소셜 제공자
  const [linked, setLinked] = useState<Provider[]>([]);            // 연결된 소셜 계정 전체
  const [loginNotice, setLoginNotice] = useState('');              // 로그인 화면 안내 문구 (만료 등)
  const [noti, setNoti] = useState(INIT_NOTI);                     // 알림 설정
  const [locConsent, setLocConsentRaw] = useState(false);
  const [picked, setPicked] = useState(GPS_LOCATION); // 직접 지정한 지역 (FS-LOC-003)
  // 휴대폰 GPS 로 확인한 현재 위치. null = 확인 중, 'fail' = 권한 거부·확인 실패
  const [gps, setGps] = useState<{ coords: LatLon; region: string } | 'fail' | null>(null);
  const [manualLoc, setManualLoc] = useState(false); // 사용자가 기준 위치를 직접 골랐는지 (FS-LOC-003)
  const [place, setPlace] = useState<Place | null>(null); // 장소 검색으로 고른 곳 = 대피 경로 출발점. 지역만 고르면 null
  const [areas, setAreas] = useState(INIT_AREAS);
  const [areaNames, setAreaNames] = useState<Record<string, string>>({}); // FS-AREA-003: 관심지역 표시명 (지역 → 별칭)
  const [groups, setGroups] = useState(INIT_GROUPS);
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [theme, setTheme] = useState<ThemeMode>('system');           // 화면 테마 (기본: 기기 설정 따름)
  const [ready, setReady] = useState(false); // 저장된 로그인 확인이 끝났는지 (끝나기 전엔 화면을 그리지 않아 로그인 화면이 깜빡이지 않음)

  // FS-LOC-001: 동의 철회 시 모든 그룹에서 내 위치공유 중단
  const setLocConsent = (on: boolean) => {
    setLocConsentRaw(on);
    if (!on) setGroups(gs => gs.map(g => ({ ...g, members: g.members.map(m =>
      m.id === ME ? { ...m, sharing: false, place: undefined, updatedMin: undefined, impact: '위치정보 미제공' as const } : m) })));
  };

  // FS-LOC-003: 위치 직접 지정 / 현재 위치로 되돌리기
  const pickLocation = (r: string, p: Place | null = null) => { setPicked(r); setManualLoc(true); setPlace(p); };
  const resetToGps = () => { setManualLoc(false); setPlace(null); };
  // 동의가 없으면 현재 위치를 쓸 수 없으므로 항상 '직접 지정'
  const isManual = !locConsent || manualLoc;

  // FS-LOC-001: 위치 동의 시 휴대폰 위치 권한을 받고 GPS 좌표 → 시·군·구 로 기본 기준 위치를 정함
  useEffect(() => {
    if (!locConsent) return;
    let alive = true;
    (async () => {
      if (!(await Location.requestForegroundPermissionsAsync()).granted) return 'fail' as const;
      const { coords } = await Location.getCurrentPositionAsync({});
      const c = { lat: coords.latitude, lon: coords.longitude };
      return { coords: c, region: await regionOf(c) };
    })().then(v => alive && setGps(v), () => alive && setGps('fail'));
    return () => { alive = false; };
  }, [locConsent]);
  // 기준 위치 이름. 확인 중·실패면 지역이 아닌 안내 문구 → 대피소·날씨는 '없음' 화면 (임의 지역으로 대신하지 않음)
  const location = isManual ? picked : gps === null ? '현재 위치 확인 중' : gps === 'fail' ? '현재 위치 확인 불가' : gps.region;
  const myPos = !isManual && gps && gps !== 'fail' ? gps.coords : null; // 지도에 표시할 내 위치
  // 동의도 없고 직접 지정도 안 함 = 기준 위치 없음. GPS 기본값을 '직접 지정'처럼 쓰지 않도록 지정 화면으로 보냄
  const needLocation = !locConsent && !manualLoc;

  // FS-AUTH-001·002: 인증 성공 후 로그인 상태 생성. 이 회원이 이 기기에서 위치 동의에 답했으면 다시 묻지 않음
  const login = async (p: Provider, info: MemberInfo) => {
    const saved = await loadLocPrefs(info.id);
    const prefs = saved ?? NO_PREFS; // 처음이거나 다른 회원이면 이전 회원의 위치 설정을 쓰지 않음
    setLocConsentRaw(prefs.locConsent); setManualLoc(prefs.manualLoc); setPicked(prefs.picked); setPlace(prefs.place);
    setNeedConsent(!saved);
    setMember(info); setProvider(p); setLinked(l => (l.includes(p) ? l : [...l, p])); setLoginNotice('');
  };
  // FS-AUTH-005: 로그아웃 (회원 데이터는 서버에 남아 있으므로 지우지 않음)
  // 프로필 이름 변경 (이메일은 소셜 계정 정보라 변경하지 않음)
  const rename = (name: string) => setMember(m => (m ? { ...m, name } : m));
  const logout = () => { logoutSession(); setMember(null); }; // 서버 세션 종료 + 저장된 토큰 삭제
  // FS-AUTH-004: 인증 만료 → 재인증 필요 상태
  const expire = () => { endSession(); setMember(null); setLoginNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.'); };

  // FS-AUTH-007 예외: 다른 구성원이 있는 그룹의 관리자면 권한 이전이 먼저 필요
  const adminBlockingGroups = groups.filter(g => g.members.length > 1 && g.members.some(m => m.id === ME && m.admin));
  // FS-AUTH-007: 인증정보·개인 데이터 삭제, 모든 그룹 탈퇴, 위치공유 중단
  const withdraw = () => {
    setGroups([]); setLinked([]); setAreas([]); setAreaNames({}); setChecked({}); setNoti({ ...INIT_NOTI, areas: [] });
    setLocConsentRaw(false); setPicked(GPS_LOCATION); setManualLoc(false); setPlace(null);
    removeItem(LOC_KEY).catch(() => {}); endSession(); setMember(null); setLoginNotice('회원 탈퇴가 완료되었습니다.');
  };

  // 위치 동의에 답한 뒤의 위치 설정을 기기에 저장 (로그아웃해도 남기고, 탈퇴하면 지움)
  const memberId = member?.id;
  useEffect(() => {
    if (!memberId || needConsent) return;
    const prefs: LocPrefs = { owner: memberId, locConsent, manualLoc, picked, place };
    setItem(LOC_KEY, JSON.stringify(prefs)).catch(() => {});
  }, [memberId, needConsent, locConsent, manualLoc, picked, place]);

  // FS-LOC-001: 동의 상태를 서버에도 기록. 로그인할 때도 한 번 보내 이전에 실패한 기록을 맞춤
  // (백엔드 세션이 없는 목업 로그인은 401 로 끝나고 무시됨)
  useEffect(() => {
    if (!memberId || needConsent) return;
    api('/api/v1/members/me/location-consent', {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ consented: locConsent }),
    }).catch(() => {});
  }, [memberId, needConsent, locConsent]);

  // 앱 시작 시 한 번: 저장된 로그인 세션이 있으면 이어서 로그인. 사용 중 세션이 끝나면(갱신 실패) 만료 처리
  useEffect(() => {
    setOnSessionExpired(expire);
    restoreSession().then(async r => { if (r) await login(r.provider, r.info); }).finally(() => setReady(true));
  }, []); // 시작 시 한 번만 (login·expire 는 setState·저장소만 씀)

  return { ready, needConsent, setNeedConsent, member, rename, provider, linked, setLinked, loginNotice, login, logout, expire,
    noti, setNoti, areaNames, setAreaNames, locConsent, setLocConsent, location, myPos, place, pickLocation, resetToGps, isManual, needLocation, areas, setAreas, groups, setGroups,
    checked, setChecked, withdraw, adminBlockingGroups, theme, setTheme };
}

const Ctx = createContext<ReturnType<typeof useAppState> | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  return <Ctx.Provider value={useAppState()}>{children}</Ctx.Provider>;
}
export function useApp() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useApp 은 AppProvider 안에서만 사용할 수 있습니다');
  return v;
}
