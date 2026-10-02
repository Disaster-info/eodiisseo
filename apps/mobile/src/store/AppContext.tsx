// 앱 전역 상태 (화면 이동은 expo-router 가 담당)
import { createContext, useContext, useState, ReactNode } from 'react';
import { GPS_LOCATION, INIT_GROUPS, Level, ME, MemberInfo, Provider } from '../data/mock';


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
  const [location, setLocation] = useState(GPS_LOCATION);
  const [manualLoc, setManualLoc] = useState(false); // 사용자가 기준 위치를 직접 골랐는지 (FS-LOC-003)
  const [areas, setAreas] = useState(INIT_AREAS);
  const [areaNames, setAreaNames] = useState<Record<string, string>>({}); // FS-AREA-003: 관심지역 표시명 (지역 → 별칭)
  const [groups, setGroups] = useState(INIT_GROUPS);
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [theme, setTheme] = useState<ThemeMode>('system');           // 화면 테마 (기본: 기기 설정 따름)

  // FS-LOC-001: 동의 철회 시 모든 그룹에서 내 위치공유 중단
  const setLocConsent = (on: boolean) => {
    setLocConsentRaw(on);
    if (!on) setGroups(gs => gs.map(g => ({ ...g, members: g.members.map(m =>
      m.id === ME ? { ...m, sharing: false, place: undefined, updatedMin: undefined, impact: '위치정보 미제공' as const } : m) })));
  };

  // FS-LOC-003: 위치 직접 지정 / 현재 위치로 되돌리기
  const pickLocation = (r: string) => { setLocation(r); setManualLoc(true); };
  const resetToGps = () => { setLocation(GPS_LOCATION); setManualLoc(false); };
  // 동의가 없으면 현재 위치를 쓸 수 없으므로 항상 '직접 지정'
  const isManual = !locConsent || manualLoc;

  // FS-AUTH-001·002: 인증 성공 후 로그인 상태 생성
  const login = (p: Provider, info: MemberInfo) => {
    setMember(info); setProvider(p); setLinked(l => (l.includes(p) ? l : [...l, p])); setLoginNotice('');
    setNeedConsent(!locConsent);
  };
  // FS-AUTH-005: 로그아웃 (회원 데이터는 서버에 남아 있으므로 지우지 않음)
  // 프로필 이름 변경 (이메일은 소셜 계정 정보라 변경하지 않음)
  const rename = (name: string) => setMember(m => (m ? { ...m, name } : m));
  const logout = () => setMember(null);
  // FS-AUTH-004: 인증 만료 → 재인증 필요 상태
  const expire = () => { setMember(null); setLoginNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.'); };

  // FS-AUTH-007 예외: 다른 구성원이 있는 그룹의 관리자면 권한 이전이 먼저 필요
  const adminBlockingGroups = groups.filter(g => g.members.length > 1 && g.members.some(m => m.id === ME && m.admin));
  // FS-AUTH-007: 인증정보·개인 데이터 삭제, 모든 그룹 탈퇴, 위치공유 중단
  const withdraw = () => {
    setGroups([]); setLinked([]); setAreas([]); setAreaNames({}); setChecked({}); setNoti({ ...INIT_NOTI, areas: [] });
    setLocConsentRaw(false); setLocation(GPS_LOCATION); setManualLoc(false);
    setMember(null); setLoginNotice('회원 탈퇴가 완료되었습니다.');
  };

  return { needConsent, setNeedConsent, member, rename, provider, linked, setLinked, loginNotice, login, logout, expire,
    noti, setNoti, areaNames, setAreaNames, locConsent, setLocConsent, location, pickLocation, resetToGps, isManual, areas, setAreas, groups, setGroups,
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
