// 백엔드 API 호출 + 로그인 세션(토큰) 관리: 저장(storage), 자동 갱신(/auth/refresh), 만료·로그아웃 처리 (FS-AUTH-004·005)
import { MemberInfo, Provider } from './mock';
import { getItem, removeItem, setItem } from './storage';

export const API = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';
const SESSION_KEY = 'eodiisseo.session';

type Tokens = { accessToken: string; refreshToken: string };
type Session = Tokens & { provider: Provider };
let session: Session | null = null;
let refreshing: Promise<boolean> | null = null;
let onExpired = () => {};

/** 갱신까지 실패해 세션이 끝났을 때 부를 함수 (앱이 로그인 화면으로 보내고 '만료' 안내) */
export function setOnSessionExpired(fn: () => void) { onExpired = fn; }

// 메모리와 저장소를 함께 갱신. 저장에 실패해도 이번 실행은 메모리로 계속
async function keep(next: Session | null) {
  session = next;
  await (next ? setItem(SESSION_KEY, JSON.stringify(next)) : removeItem(SESSION_KEY)).catch(() => {});
}
export const startSession = (tokens: Tokens, provider: Provider) => keep({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, provider });
export const endSession = () => keep(null);

const TIMEOUT_MS = 15000; // 서버가 응답하지 않으면 이 시간 뒤 실패로 처리 (앱 시작이 빈 화면에서 멈추지 않게)

/** 로그인 없이 부르는 API. 서버에 닿지 못하면(꺼짐·주소 오류·응답 없음) 사용자 문구로 */
export async function request(path: string, init: RequestInit = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    return await fetch(`${API}${path}`, { ...init, signal: ctrl.signal });
  } catch {
    throw new Error(ctrl.signal.aborted
      ? '서버 응답이 없어요. 잠시 후 다시 시도해 주세요.'
      : '서버에 연결하지 못했어요. 네트워크 상태를 확인하고 다시 시도해 주세요.');
  } finally {
    clearTimeout(timer);
  }
}

/** 시간 제한: ms 안에 끝나지 않으면 message 로 실패 (휴대폰 GPS 처럼 응답이 한없이 늦을 수 있는 작업용) */
export function withTimeout<T>(work: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error(message)), ms); });
  return Promise.race([work, late]).finally(() => clearTimeout(timer));
}

// refresh 토큰은 쓸 때마다 새것으로 바뀌고, 이미 쓴 것을 다시 보내면 서버가 세션을 끊음 → 갱신은 동시에 한 번만
function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    const current = session;
    if (!current) return false;
    const res = await request('/api/v1/auth/refresh', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: current.refreshToken }),
    });
    if (!res.ok) {
      if (res.status === 400 || res.status === 401) await endSession(); // 만료·폐기된 세션
      return false;
    }
    await keep({ ...current, ...(await res.json() as Tokens) });
    return true;
  })().finally(() => { refreshing = null; });
  return refreshing;
}

/** 로그인이 필요한 API. 액세스 토큰이 만료(401)되면 한 번 갱신하고 다시 보냄. 갱신도 실패하면 세션 종료 */
export async function api(path: string, init: RequestInit = {}) {
  const hadSession = !!session;
  const send = () => request(path, { ...init, headers: { ...init.headers, ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}) } });
  let res = await send();
  if (res.status === 401 && hadSession) {
    if (await refresh()) res = await send();
    if (res.status === 401) { await endSession(); onExpired(); }
  }
  return res;
}

/** 로그인한 회원 정보. 백엔드가 이메일·가입일은 주지 않으므로 비워 둠 (지어내지 않음) */
export async function fetchMember(): Promise<MemberInfo | null> {
  const res = await api('/api/v1/members/me');
  if (!res.ok) return null;
  const me: { memberId: string; name?: string; roles?: string[] } = await res.json();
  return { id: me.memberId, name: me.name ?? '회원', email: '', joinedAt: '', operator: !!me.roles?.includes('OPS') };
}

/**
 * FS-AUTH-001·002: 소셜 인증 결과로 백엔드 로그인 → 세션 저장 → 회원 정보.
 * body 는 웹이면 인가 코드({ code, redirectUri, codeVerifier }), 앱이면 제공자 토큰({ idToken } / { accessToken })
 */
export async function signIn(provider: Provider, body: object): Promise<MemberInfo> {
  const res = await request(`/api/v1/auth/${provider.toLowerCase()}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.message ?? `로그인 서버 오류 (${res.status})`);
  // FS-AUTH-007: 탈퇴 유예 중인 계정은 세션 없이 복구 가능 기한만 옴
  if (json.accountStatus === 'WITHDRAWAL_PENDING') {
    throw new Error(`탈퇴 처리 중인 계정이에요. ${String(json.recoverableUntil).slice(0, 10)}까지 복구할 수 있어요.`);
  }
  if (!json.accessToken || !json.refreshToken) throw new Error('로그인 응답이 올바르지 않아요. 잠시 후 다시 시도해 주세요.');
  await startSession(json, provider); // 토큰 저장 → 다시 열어도 로그인 유지, 만료되면 자동 갱신
  // 운영 관리 권한(OPS)은 회원 정보 조회로 확인. 실패하면 로그인 응답의 값만, 권한 없음으로 둠
  const info = await fetchMember().catch(() => null);
  return info ?? { id: json.memberId, name: json.name, email: '', joinedAt: '', operator: false };
}

/** 앱 시작 시: 저장된 세션이 있으면 이어서 로그인. 없거나 끝났으면 null */
export async function restoreSession(): Promise<{ provider: Provider; info: MemberInfo } | null> {
  const raw = await getItem(SESSION_KEY).catch(() => null);
  if (!raw) return null;
  try {
    session = JSON.parse(raw) as Session;
  } catch {
    await endSession();
    return null;
  }
  try {
    const info = await fetchMember(); // 액세스 토큰이 만료됐으면 api() 가 갱신까지 시도
    return info && session ? { provider: session.provider, info } : null;
  } catch {
    return null; // 서버에 닿지 못함: 토큰은 남겨 두고 이번에는 로그인 화면
  }
}

/** 로그아웃: 서버 세션을 끊고 저장된 토큰 삭제. 서버에 닿지 못해도 이 기기에서는 로그아웃 */
export async function logoutSession() {
  if (session) await api('/api/v1/auth/logout', { method: 'POST' }).catch(() => {});
  await endSession();
}
