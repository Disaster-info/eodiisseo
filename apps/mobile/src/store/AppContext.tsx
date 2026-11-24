// 앱 전역 상태 (화면 이동은 expo-router 가 담당). 그룹·관심지역·알림 설정은 서버가 원본, 여기는 그 사본
import * as Location from 'expo-location';
import { createContext, useCallback, useContext, useEffect, useRef, useState, ReactNode } from 'react';
import { AppState } from 'react-native';
import { notify } from '../components/ui';
import { endSession, logoutSession, restoreSession, setOnSessionExpired, withTimeout } from '../data/api';
import { Noti, areaApi, fetchGroups, getConsent, notiApi, patchConsent, putLocation, withdrawMember } from '../data/server';
import { getItem, removeItem, setItem } from '../data/storage';
import { LatLon, Place, regionOf } from '../data/tmap';
import { Area, Group, MemberInfo, Provider } from '../data/types';

// FS-LOC-001·003: 위치 동의·기준 위치를 기기에 저장 → 다시 열어도 그대로. owner 로 회원을 구분 (다른 회원이면 다시 물음)
const LOC_KEY = 'eodiisseo.location';
const THEME_KEY = 'eodiisseo.theme'; // 화면 테마: 기기 설정 (회원과 무관)
// 시·군·구 코드 → 지역 이름('서울 강남구'). 서버는 코드와 표시 이름(별칭)만 주므로, 별칭을 붙여도 재난 지역과 비교할 수 있게 기억해 둠 (회원과 무관한 공공 정보)
const REGION_KEY = 'eodiisseo.regionNames';
const GPS_EVERY_MS = 60_000;         // 앱을 보고 있는 동안 GPS 를 다시 재는 간격 (백엔드 위치 갱신 목표 60초)
type LocPrefs = { owner: string; locConsent: boolean; manualLoc: boolean; picked: string; place: Place | null };
const NO_PREFS = { locConsent: false, manualLoc: false, picked: '', place: null };
async function loadLocPrefs(owner: string): Promise<LocPrefs | null> {
  const raw = await getItem(LOC_KEY).catch(() => null);
  try {
    const p = raw ? (JSON.parse(raw) as LocPrefs | null) : null;
    return p && typeof p === 'object' && p.owner === owner && typeof p.picked === 'string' ? p : null;
  } catch {
    return null;
  }
}

export type ThemeMode = 'system' | 'light' | 'dark';
const INIT_NOTI: Noti = { on: true, areaIds: [], minLevel: '주의보', night: false };
// 서버에서 받는 중·실패를 빈 목록과 구분 (빈 목록을 '그룹 없음'으로 단정하지 않게)
export type Loaded = 'loading' | 'ok' | 'error';

function useAppState() {
  const [member, setMember] = useState<MemberInfo | null>(null); // null = 비로그인 상태 (라우터가 로그인 화면으로 보냄)
  const [needConsent, setNeedConsent] = useState(false);           // 로그인 직후 위치 동의 화면을 보여줄지
  const [provider, setProvider] = useState<Provider>('Google');   // 로그인에 사용한 소셜 제공자
  const [linked, setLinked] = useState<Provider[]>([]);            // 이번 로그인에서 연결이 확인된 소셜 계정
  const [loginNotice, setLoginNotice] = useState('');              // 로그인 화면 안내 문구 (만료 등)
  const [noti, setNotiRaw] = useState(INIT_NOTI);                  // 알림 설정 (서버 값)
  const [notiState, setNotiState] = useState<Loaded>('loading');   // 서버 값을 받기 전·실패면 기본값을 실제 설정처럼 보이지 않게
  const [locConsent, setLocConsentRaw] = useState(false);
  const [picked, setPicked] = useState(''); // 직접 지정한 지역 (FS-LOC-003). '' = 아직 지정 안 함
  // 휴대폰 GPS 로 확인한 현재 위치. null = 확인 중, 'fail' = 권한 거부·확인 실패
  const [gps, setGps] = useState<{ coords: LatLon; region: string; at: number } | 'fail' | null>(null);
  const [manualLoc, setManualLoc] = useState(false); // 사용자가 기준 위치를 직접 골랐는지 (FS-LOC-003)
  const [place, setPlace] = useState<Place | null>(null); // 장소 검색으로 고른 곳 = 대피 경로·대피소 검색 기준점
  const [rawAreas, setAreas] = useState<Area[]>([]);
  const [regionNames, setRegionNames] = useState<Record<string, string>>({});
  useEffect(() => {
    getItem(REGION_KEY).then(raw => {
      const v = raw ? JSON.parse(raw) : null;
      if (v && typeof v === 'object' && !Array.isArray(v)) setRegionNames(Object.fromEntries(Object.entries(v).filter(([, x]) => typeof x === 'string')) as Record<string, string>);
    }).catch(() => {});
  }, []);
  /** 관심지역을 등록·변경할 때 고른 지역 이름을 코드와 함께 기억 */
  const rememberRegion = (code: string, region: string) => setRegionNames(m => {
    const next = { ...m, [code]: region };
    setItem(REGION_KEY, JSON.stringify(next)).catch(() => {});
    return next;
  });
  // 다른 기기에서 등록해 이름을 모르는 지역은 표시 이름으로 비교 (서버 표시 이름의 기본값이 지역 이름)
  const areas = rawAreas.map(a => ({ ...a, region: regionNames[a.code] ?? a.name, regionKnown: a.code in regionNames }));
  const [groups, setGroups] = useState<Group[]>([]);
  const [groupsState, setGroupsState] = useState<Loaded>('loading');
  const [theme, setThemeRaw] = useState<ThemeMode>('system'); // 화면 테마 (기본: 기기 설정 따름)
  const setTheme = (t: ThemeMode) => { setThemeRaw(t); setItem(THEME_KEY, t).catch(() => {}); };
  useEffect(() => {
    getItem(THEME_KEY).then(t => { if (t === 'light' || t === 'dark' || t === 'system') setThemeRaw(t); }).catch(() => {});
  }, []);
  const [ready, setReady] = useState(false); // 저장된 로그인 확인이 끝났는지 (끝나기 전엔 화면을 그리지 않아 로그인 화면이 깜빡이지 않음)
  const memberId = member?.id;
  const operator = !!member?.operator;
  // 지금 로그인한 회원 번호: 로그아웃·계정 전환 뒤 늦게 온 이전 회원의 응답을 버리는 데 씀
  const owner = useRef<string | undefined>(undefined);
  useEffect(() => { owner.current = memberId; }, [memberId]);

  // ── 서버 데이터 다시 받기 (늦게 온 이전 회원 응답은 버림) ──
  const reloadGroups = useCallback(async () => {
    const me = owner.current;
    if (!me) return;
    try {
      const gs = await fetchGroups(me);
      if (owner.current === me) { setGroups(gs); setGroupsState('ok'); }
    } catch {
      if (owner.current === me) setGroupsState('error');
    }
  }, []);
  const reloadAreas = useCallback(async () => {
    const me = owner.current;
    const list = await areaApi.list();
    if (owner.current === me) setAreas(list);
  }, []);
  // 알림 설정: 화면에 먼저 반영하고 서버 결과로 맞춤. 실패하면 서버 값으로 되돌리고 오류를 던짐 (화면이 안내)
  // 빠르게 여러 번 바꿔도 마지막 선택이 남도록 요청을 한 줄로 보내고, 마지막 요청의 응답만 화면에 반영
  const notiQueue = useRef<Promise<unknown>>(Promise.resolve());
  const notiSeq = useRef(0);
  const loadNoti = useCallback(() => {
    const me = owner.current, my = ++notiSeq.current;
    notiApi.get().then(n => { if (owner.current === me && my === notiSeq.current) { setNotiRaw(n); setNotiState('ok'); } },
      () => { if (owner.current === me && my === notiSeq.current) setNotiState('error'); });
  }, []);
  const setNoti = (change: Partial<Noti>) => {
    const me = owner.current, my = ++notiSeq.current;
    setNotiRaw(n => ({ ...n, ...change }));
    const job = notiQueue.current.catch(() => {}).then(() => {
      if (owner.current !== me) throw new Error('계정이 바뀌었어요.');
      return notiApi.patch(change);
    });
    notiQueue.current = job;
    return job.then(saved => { if (owner.current === me && my === notiSeq.current) setNotiRaw(saved); },
      e => { if (owner.current === me) loadNoti(); throw e; }); // 실패하면 서버 값으로 되돌림
  };
  // 로그인하면 이 회원의 서버 데이터를 받음 (운영자는 그룹·관심지역을 쓰지 않음)
  useEffect(() => {
    if (!memberId || operator) return;
    reloadGroups();
    reloadAreas().catch(() => {});
    loadNoti();
  }, [memberId, operator, reloadGroups, reloadAreas, loadNoti]);

  // FS-LOC-001: 동의 상태를 서버에 기록 (철회하면 서버가 내 위치 삭제 + 모든 그룹 위치공유 OFF). 그 뒤 그룹을 다시 받아 화면을 맞춤
  // 빠르게 켰다 껐다 해도 순서가 뒤집히지 않도록 한 줄로 보냄 (마지막 값이 서버에 남음)
  // 계정이 바뀐 뒤에는 이전 계정의 대기 요청을 보내지 않음. 철회가 서버에 반영되지 않으면 알림 (공유가 계속될 수 있으므로)
  const consentQueue = useRef(Promise.resolve());
  const syncConsent = (on: boolean) => {
    const me = owner.current;
    consentQueue.current = consentQueue.current.then(async () => {
      if (owner.current !== me) return;
      try {
        await patchConsent(on);
        if (!on && owner.current === me) reloadGroups();
      } catch {
        if (owner.current !== me) return;
        if (!on) notify('위치 공유 중단이 서버에 반영되지 않았어요', '네트워크를 확인한 뒤 위치정보 이용 동의를 다시 켰다 꺼 주세요. 그 전까지 그룹원에게 마지막 위치가 보일 수 있어요.');
        reloadGroups();
      }
    });
  };
  const setLocConsent = (on: boolean) => {
    setLocConsentRaw(on);
    if (!on) { setGps(null); setGroups(gs => gs.map(g => ({ ...g, members: g.members.map(m => m.id === memberId ? { ...m, sharing: false, lat: undefined, lon: undefined, updatedAt: undefined, impact: '위치정보 미제공' as const } : m) }))); }
    if (memberId) syncConsent(on); // 사용자가 직접 누른 경우에만 호출됨 → 바로 서버에 기록
  };
  // 로그인·복원 시 서버 동의 상태가 기준. 이 기기에 남은 옛 '동의'로 서버 값을 덮어쓰지 않음
  // (다른 기기에서 철회했으면 여기서도 끔). 이 기기에서 철회했는데 서버가 아직 동의면 철회만 다시 보냄
  useEffect(() => {
    if (!memberId || needConsent || operator) return;
    const me = owner.current;
    consentQueue.current = consentQueue.current.then(async () => {
      if (owner.current !== me) return;
      const server = await getConsent().catch(() => null);
      if (!server || owner.current !== me) return;
      if (locConsent && !server.consented) { setLocConsentRaw(false); setGps(null); }
      else if (!locConsent && server.consented) syncConsent(false);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 로그인·동의 답변 시점에만
  }, [memberId, needConsent]);

  // FS-LOC-003: 위치 직접 지정 / 현재 위치로 되돌리기
  const pickLocation = (r: string, p: Place | null = null) => { setPicked(r); setManualLoc(true); setPlace(p); };
  const [gpsTick, setGpsTick] = useState(0);
  const resetToGps = () => { setManualLoc(false); setPlace(null); setGps(null); setGpsTick(t => t + 1); };
  const isManual = !locConsent || manualLoc; // 동의가 없으면 현재 위치를 쓸 수 없으므로 항상 '직접 지정'

  // FS-LOC-001: 동의 시 휴대폰 위치 권한 → GPS 좌표 → 시·군·구. 확인한 좌표는 서버에도 올림 (그룹 위치공유·영향상태용)
  // 앱으로 돌아올 때와 보고 있는 동안 1분마다 다시 잼 (이동했는데 예전 지역을 현재 위치로 보여 주지 않게)
  useEffect(() => {
    if (!locConsent || !memberId || operator) return;
    const again = () => setGpsTick(t => t + 1);
    const timer = setInterval(() => { if (AppState.currentState === 'active') again(); }, GPS_EVERY_MS);
    const sub = AppState.addEventListener('change', st => { if (st === 'active') again(); });
    return () => { clearInterval(timer); sub.remove(); };
  }, [locConsent, memberId, operator]);
  useEffect(() => {
    if (!locConsent || !memberId || operator) return;
    let alive = true;
    (async () => {
      if (!(await Location.requestForegroundPermissionsAsync()).granted) return 'fail' as const;
      // 실내 등에서 위치가 한없이 늦게 올 수 있어 20초 넘으면 '확인 불가' (기준 위치 직접 지정 안내로 이어짐)
      const { coords } = await withTimeout(Location.getCurrentPositionAsync({}), 20000, '현재 위치 확인 시간 초과');
      const c = { lat: coords.latitude, lon: coords.longitude };
      // 서버는 동의가 저장된 뒤에만 위치를 받으므로(아니면 403) 대기 중인 동의 저장이 끝난 뒤 올림. 로그아웃·계정 전환 뒤면 올리지 않음
      consentQueue.current.then(() => {
        if (alive && owner.current === memberId) putLocation(c.lat, c.lon).catch(() => {}); // 실패는 다음 측정 때 다시
      });
      return { coords: c, region: await regionOf(c), at: Date.now() };
    })().then(v => alive && setGps(v),
      // 다시 재다 실패하면: 처음이면 '확인 불가', 이전 값이 5분 넘게 오래됐으면 '확인 불가' (오래된 위치를 현재 위치로 쓰지 않음)
      () => alive && setGps(g => (g && g !== 'fail' && Date.now() - g.at < 5 * GPS_EVERY_MS ? g : 'fail')));
    return () => { alive = false; };
  }, [locConsent, memberId, operator, gpsTick]);
  // 기준 위치를 확인했는지: ok = 지역을 앎, loading = GPS 확인 중, fail = 권한 거부·확인 실패·지정 안 함
  const locState = isManual ? (picked ? 'ok' : 'fail') : gps === null ? 'loading' : gps === 'fail' ? 'fail' : 'ok';
  const gpsRegion = locConsent && gps && gps !== 'fail' ? gps.region : null; // 그룹에 공유할 위치 = GPS 로 확인한 지역만
  const location = isManual ? (picked || '기준 위치 없음') : gps === null ? '현재 위치 확인 중' : gps === 'fail' ? '현재 위치 확인 불가' : gps.region;
  const myPos = !isManual && gps && gps !== 'fail' ? gps.coords : null; // 지도에 표시할 내 위치
  // 대피소·날씨를 찾을 좌표: GPS > 장소 검색으로 고른 곳. 지역만 골랐으면 null (화면이 지역 이름으로 좌표를 찾음)
  const basePos = myPos ?? (isManual ? place : null);
  const needLocation = !locConsent && !manualLoc; // 동의도 없고 직접 지정도 안 함 → 지정 화면으로

  // 계정마다 다른 데이터는 로그인·로그아웃 때 비움 (다른 회원의 그룹·관심지역이 보이지 않게)
  const clearAccount = () => {
    setGroups([]); setGroupsState('loading'); setAreas([]); setNotiRaw(INIT_NOTI); setNotiState('loading'); setLinked([]); setGps(null);
  };
  // FS-AUTH-001·002: 인증 성공 후 로그인 상태 생성. 이 회원이 이 기기에서 위치 동의에 답했으면 다시 묻지 않음
  // 운영자는 위치 동의를 건너뜀 (내 위치를 쓰지 않음). 기준 위치는 직접 지정
  const loginTry = useRef(0);
  // 반환: 로그인 상태를 적용했는지 (취소·다른 로그인이면 false)
  const login = async (p: Provider, info: MemberInfo, isCancelled: () => boolean = () => false): Promise<boolean> => {
    const my = ++loginTry.current;
    const saved = await loadLocPrefs(info.id);
    if (my !== loginTry.current || isCancelled()) return false; // 그 사이 다른 로그인·취소
    const prefs = saved ?? (info.operator ? { ...NO_PREFS, manualLoc: true } : NO_PREFS);
    clearAccount();
    setLocConsentRaw(prefs.locConsent); setManualLoc(prefs.manualLoc); setPicked(prefs.picked); setPlace(prefs.place);
    setNeedConsent(!saved && !info.operator);
    setMember(info); setProvider(p); setLinked([p]); setLoginNotice('');
    return true;
  };
  const rename = (name: string) => setMember(m => (m ? { ...m, name } : m)); // ponytail: 이름 변경 API 가 없어 이 기기 화면에만
  const logout = () => { loginTry.current++; logoutSession(); setMember(null); clearAccount(); }; // 서버 세션 종료 + 저장된 토큰 삭제
  // FS-AUTH-004: 인증 만료 → 재인증 필요 상태
  const expire = () => { loginTry.current++; endSession(); setMember(null); clearAccount(); setLoginNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.'); };

  // FS-AUTH-007 예외: 관리자인 그룹이 하나라도 있으면 서버가 탈퇴를 막음 (혼자인 그룹도) → 권한 이전 또는 그룹 삭제가 먼저
  const adminBlockingGroups = groups.filter(g => g.admin);
  // FS-AUTH-007: 서버 탈퇴가 성공한 뒤에만 이 기기 데이터를 지우고 완료 안내. 실패하면 오류를 던짐 (화면이 안내, 로그인 유지)
  const withdraw = async () => {
    await withdrawMember();
    loginTry.current++;
    removeItem(LOC_KEY).catch(() => {});
    await endSession();
    setLocConsentRaw(false); setPicked(''); setManualLoc(false); setPlace(null);
    setMember(null); clearAccount(); setLoginNotice('회원 탈퇴가 접수되었습니다. 7일 안에 휴대폰 앱에서 같은 계정으로 로그인하면 복구할 수 있어요.');
  };

  // 위치 동의에 답한 뒤의 위치 설정을 기기에 저장 (로그아웃해도 남기고, 탈퇴하면 지움)
  useEffect(() => {
    if (!memberId || needConsent) return;
    const prefs: LocPrefs = { owner: memberId, locConsent, manualLoc, picked, place };
    setItem(LOC_KEY, JSON.stringify(prefs)).catch(() => {});
  }, [memberId, needConsent, locConsent, manualLoc, picked, place]);

  // 앱 시작 시 한 번: 저장된 로그인 세션이 있으면 이어서 로그인. 사용 중 세션이 끝나면(갱신 실패) 만료 처리
  useEffect(() => {
    setOnSessionExpired(expire);
    restoreSession().then(async r => {
      if (!r) return;
      await login(r.provider, r.info);
      if (r.offline) notify('서버에 연결하지 못했어요', '저장된 로그인 정보로 열었어요. 재난 정보가 최신이 아닐 수 있으니 공식 재난문자·방송 안내를 함께 확인하고, 잠시 후 아래로 당겨 새로고침하세요.');
    }).catch(() => {}).finally(() => setReady(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 시작 시 한 번만 (login·expire 는 setState·저장소만 씀)
  }, []);

  return { ready, needConsent, setNeedConsent, member, rename, provider, linked, loginNotice, login, logout, expire,
    noti, notiState, loadNoti, setNoti, locConsent, setLocConsent, location, locState, gpsRegion, myPos, basePos, place, pickLocation, resetToGps, isManual, needLocation,
    areas, reloadAreas, rememberRegion, groups, groupsState, reloadGroups, withdraw, adminBlockingGroups, theme, setTheme };
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
