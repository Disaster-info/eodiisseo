// 백엔드 API 호출 + 로그인 세션(토큰) 관리: 저장(storage), 자동 갱신(/auth/refresh), 만료·로그아웃 처리 (FS-AUTH-004·005)
import { Platform } from 'react-native';
import { MemberInfo, Provider } from './types';
import { getItem, removeItem, setItem } from './storage';

// 주소 끝의 / 는 떼어 냄 (https://api.example.com/ 로 넣어도 //api/... 가 되지 않게)
export const API = (process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
const SESSION_KEY = 'eodiisseo.session';
// 웹(M5): refresh 토큰은 서버가 HttpOnly 쿠키로만 줌 → 브라우저 저장소(localStorage)에 두지 않음 (페이지 스크립트가 읽지 못하게)
// 앱(iOS·Android)은 지금처럼 응답 본문으로 받아 SecureStore 에 보관
const WEB = Platform.OS === 'web';

// refreshToken: 앱만 있음 (웹은 쿠키). 갱신할 때마다 access·refresh 가 함께 바뀌므로 '같은 토큰인지'는 accessToken 으로 비교
type Tokens = { accessToken: string; refreshToken?: string };
// info: 마지막으로 확인한 회원 정보. 앱 시작 때 서버에 닿지 못해도 이 정보로 열어 재난 화면을 쓸 수 있게
type Session = Tokens & { provider: Provider; info?: MemberInfo };
let session: Session | null = null;
// 세션 세대 번호: 세션이 바뀔 때마다(로그인·갱신·로그아웃) 1 증가.
// 늦게 끝난 요청은 시작할 때의 번호와 지금 번호가 다르면 결과를 버림 (로그아웃·새 로그인을 덮어쓰지 않게)
let gen = 0;
let refreshing: Promise<Refreshed> | null = null;
let onExpired = () => {};

/** 갱신까지 실패해 세션이 끝났을 때 부를 함수 (앱이 로그인 화면으로 보내고 '만료' 안내) */
export function setOnSessionExpired(fn: () => void) { onExpired = fn; }

// 메모리와 저장소를 함께 갱신. 저장에 실패해도 이번 실행은 메모리로 계속
async function keep(next: Session | null) {
  const prev = session;
  session = next;
  gen++;
  if (next) return setItem(SESSION_KEY, JSON.stringify(next)).catch(() => {});
  // 세션 종료: 다른 탭이 이미 다른 계정으로 로그인해 둔 저장값이면 지우지 않음 (그 탭까지 로그아웃되지 않게)
  if (otherAccount(await readStored(), prev)) return;
  await removeItem(SESSION_KEY).catch(() => {});
}
const readStored = () => getItem(SESSION_KEY).then(v => (v ? JSON.parse(v) as Session : null)).catch(() => null);
// 웹: refresh 쿠키는 탭끼리 공유 → 다른 탭에서 다른 계정으로 로그인하면 쿠키가 그 계정 것으로 바뀜
// 저장소의 세션이 이 탭과 다른 회원이면 true (이 탭의 세션은 끝난 것. 그 쿠키로 갱신·로그아웃하지 않음)
const otherAccount = (stored: Session | null, mine: Session | null) =>
  WEB && !!stored?.info?.id && !!mine?.info?.id && stored.info.id !== mine.info.id;
// access 토큰(JWT)의 회원 id. 웹에서 갱신 응답이 이 탭 회원의 토큰인지 확인용
const subOf = (jwt: string) => {
  try { return JSON.parse(atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub as string | undefined; } catch { return undefined; }
};
// 계정 번호: 로그인·로그아웃 때만 1 증가 (토큰 갱신은 그대로). 요청 도중 계정이 바뀌면 그 요청은 새 계정 토큰으로 다시 보내지 않음
let account = 0;
export const startSession = (tokens: Tokens, provider: Provider) => { account++; return keep({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, provider }); };
export const endSession = () => { account++; return keep(null); };
/** 지금 세션의 계정 번호 (로그인 시도가 만든 세션이 아직 그대로인지 확인용) */
export const sessionAccount = () => account;

const TIMEOUT_MS = 15000; // 서버가 응답하지 않으면 이 시간 뒤 실패로 처리 (앱 시작이 빈 화면에서 멈추지 않게)

/** 응답: 본문까지 다 받은 뒤 돌려줌 (본문이 중간에 멈춰도 제한 시간 안에서 실패). json() 은 본문이 비었거나 JSON 이 아니면 Error */
export type Reply = { ok: boolean; status: number; json: () => Promise<any> };

/** 시간 제한이 있는 fetch: 연결·헤더·본문 수신 전체를 ms 안에. 서버에 닿지 못하면(꺼짐·주소 오류·응답 없음) 사용자 문구로 */
export async function fetchText(url: string, init: RequestInit = {}, ms = TIMEOUT_MS): Promise<Reply> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch(url, { ...init, signal: ctrl.signal });
    const text = await res.text();
    return { ok: res.ok, status: res.status, json: async () => JSON.parse(text) };
  } catch {
    throw new Error(ctrl.signal.aborted
      ? '서버 응답이 없어요. 잠시 후 다시 시도해 주세요.'
      : '서버에 연결하지 못했어요. 네트워크 상태를 확인하고 다시 시도해 주세요.');
  } finally {
    clearTimeout(timer);
  }
}

/** 로그인 없이 부르는 API */
// 웹의 인증 API(/api/v1/auth/*)만 쿠키를 주고받음 + 쿠키 방식 표시 헤더 (서버는 이 헤더가 있어야 쿠키를 읽음 → 다른 사이트의 위조 요청은 CORS 사전 확인에서 막힘)
export const request = (path: string, init: RequestInit = {}, ms?: number) => fetchText(`${API}${path}`, WEB && path.startsWith('/api/v1/auth/')
  ? { ...init, credentials: 'include', headers: { ...(init.headers as Record<string, string> | undefined), 'X-Auth-Transport': 'cookie' } }
  : init, ms);
// 갱신·로그아웃 본문: 앱은 refresh 토큰, 웹은 본문 없이 쿠키로 (예전 버전이 저장해 둔 토큰이 남아 있으면 한 번 보내 쿠키로 옮김)
const refreshBody = (s: Session): RequestInit => s.refreshToken
  ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: s.refreshToken }) } : {};

/** 시간 제한: ms 안에 끝나지 않으면 message 로 실패 (휴대폰 GPS 처럼 응답이 한없이 늦을 수 있는 작업용) */
export function withTimeout<T>(work: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error(message)), ms); });
  return Promise.race([work, late]).finally(() => clearTimeout(timer));
}

// 갱신 결과: ok = 새 토큰, expired = 서버가 세션을 끊음(재로그인 필요), error = 서버 장애·네트워크·그 사이 세션 바뀜 (지금 세션은 그대로)
type Refreshed = 'ok' | 'expired' | 'error';
// refresh 토큰은 쓸 때마다 새것으로 바뀌고, 이미 쓴 것을 다시 보내면 서버가 세션을 끊음 → 갱신은 동시에 한 번만
// 웹에서 탭 여러 개가 같은 저장소 세션을 쓰면 각 탭이 같은 refresh 토큰을 보내 서버가 '재사용'으로 보고 세션을 끊음
// → 브라우저 탭 간 잠금(navigator.locks)으로 한 탭씩, 잠금을 얻은 뒤 저장소를 다시 읽어 다른 탭이 받은 새 토큰이면 그대로 씀
// (앱에는 navigator.locks 가 없어 바로 실행 — 앱은 실행 하나뿐이라 위의 refreshing 공유로 충분)
const withRefreshLock = <T>(work: () => Promise<T>): Promise<T> => {
  const locks = (globalThis as { navigator?: { locks?: { request: (name: string, cb: () => Promise<T>) => Promise<T> } } }).navigator?.locks;
  return locks ? locks.request('eodiisseo.refresh', work) : work();
};
// 회원 정보를 세션에 덧붙여 저장 (로그인 직후·앱 시작 때). 회원 조회 중에 다른 탭이 토큰을 갱신했을 수 있음
// → 갱신과 같은 잠금 안에서 저장소를 다시 읽고, 같은 회원의 새 토큰이면 그 토큰을 씀 (옛 토큰으로 덮어쓰면 서버가 재사용으로 보고 세션을 끊음)
// 저장소가 다른 회원의 세션이거나(다른 탭에서 계정 전환) 비었으면(다른 탭에서 로그아웃) 덮어쓰지 않고 이 탭의 메모리만 갱신
// mine = 회원 조회를 시작할 때의 계정 번호. 잠금을 기다리는 사이 로그아웃·다른 로그인이 있었으면 false (호출자가 실패로 처리)
function keepInfo(info: MemberInfo, mine: number) {
  return withRefreshLock(async () => {
    if (mine !== account || !session) return false;
    // undefined = 저장소를 읽지 못함(막힘·손상) → 평소처럼 저장 시도. null = 저장된 세션 없음
    const stored = await getItem(SESSION_KEY).then(v => (v ? JSON.parse(v) as Session : null)).catch(() => undefined);
    const current = session;
    if (mine !== account || !current) return false;
    if (stored === undefined || stored?.accessToken === current.accessToken) await keep({ ...current, info });
    else if (stored?.info?.id === info.id) await keep({ ...current, accessToken: stored.accessToken, refreshToken: stored.refreshToken, info });
    else session = { ...current, info };
    return true;
  });
}
function refresh(): Promise<Refreshed> {
  refreshing ??= withRefreshLock(async (): Promise<Refreshed> => {
    const current = session, myGen = gen;
    if (!current) return 'error';
    const stored = await readStored();
    if (myGen !== gen) return 'error';
    if (otherAccount(stored, current)) return 'expired'; // 다른 탭이 다른 계정으로 로그인함 → 이 탭은 다시 로그인
    if (stored && stored.accessToken !== current.accessToken && stored.info?.id === current.info?.id) {
      await keep({ ...current, accessToken: stored.accessToken, refreshToken: stored.refreshToken });
      return 'ok';
    }
    const res = await request('/api/v1/auth/refresh', { method: 'POST', ...refreshBody(current) }).catch(() => null);
    if (myGen !== gen || !res) return 'error'; // 그 사이 로그아웃·다른 로그인이면 이 결과는 버림
    if (res.status === 400 || res.status === 401) return 'expired'; // 만료·폐기된 세션
    const tokens = res.ok ? await res.json().catch(() => null) as Tokens | null : null;
    if (!tokens?.accessToken || (!WEB && !tokens.refreshToken)) return 'error'; // 5xx 등 일시 장애: 유효한 refresh 토큰을 버리지 않음
    // 웹: 그 사이 공유 쿠키가 다른 계정 것으로 바뀌었으면 받은 토큰은 이 탭 회원 것이 아님 → 쓰지 않고 다시 로그인
    if (WEB && current.info?.id && subOf(tokens.accessToken) !== current.info.id) return 'expired';
    await keep({ ...current, accessToken: tokens.accessToken, refreshToken: tokens.refreshToken }); // 웹은 refreshToken 이 없어 저장소에서 빠짐
    return 'ok';
  }).finally(() => { refreshing = null; });
  return refreshing;
}

/** 로그인이 필요한 API. 액세스 토큰이 만료(401)되면 한 번 갱신하고 다시 보냄. 서버가 세션을 끊었을 때만 세션 종료 */
// ms: 응답 제한 시간 (기본 15초. AI 답변처럼 오래 걸리는 요청만 늘림)
export async function api(path: string, init: RequestInit = {}, ms?: number) {
  const myAccount = account;
  const send = () => request(path, { ...init, headers: { ...init.headers, ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}) } }, ms);
  const sentGen = gen;
  let res = await send();
  if (res.status === 401 && session && myAccount === account) {
    if (gen !== sentGen) return send(); // 그 사이 다른 요청이 이미 같은 계정 토큰을 갱신함 → 새 토큰으로 한 번만 다시
    const myGen = gen;
    const r = await refresh();
    // 갱신 요청이 서버 장애·네트워크 오류로 실패: 세션이 끊긴 게 아니므로 401 을 그대로 돌려주지 않음 ('로그인 만료'로 오판해 토큰을 지우지 않게)
    if (r === 'error' && myAccount === account) throw new Error('서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.');
    if (r === 'ok' && myAccount === account) res = await send();
    // 갱신이 만료로 끝났거나 새 토큰으로도 401 → 재로그인. 그 사이 로그아웃·새 로그인으로 세션이 바뀌었으면 건드리지 않음
    const lost = r === 'expired' ? gen === myGen : r === 'ok' && res.status === 401 && gen === myGen + 1;
    if (lost) { await endSession(); onExpired(); }
  }
  return res;
}

/** 로그인한 회원 정보. 백엔드가 이메일·가입일은 주지 않으므로 비워 둠 (지어내지 않음) */
// 반환 null = 세션이 끊김(401·403, 다시 로그인 필요). 서버 장애·네트워크 오류는 Error (세션은 그대로)
export async function fetchMember(): Promise<MemberInfo | null> {
  const res = await api('/api/v1/members/me');
  if (res.status === 401 || res.status === 403) return null;
  if (!res.ok) throw new Error(`회원 정보 조회 실패 (${res.status})`);
  const me: { memberId?: unknown; name?: unknown; roles?: unknown } = await res.json();
  if (typeof me?.memberId !== 'string' || !me.memberId) throw new Error('회원 정보 응답이 올바르지 않아요.');
  return { id: me.memberId, name: typeof me.name === 'string' && me.name ? me.name : '회원', email: '', joinedAt: '',
    operator: Array.isArray(me.roles) && me.roles.includes('OPS') };
}

/** 로그인 화면에서 취소한 시도 (화면은 이미 취소 상태라 안내 문구를 띄우지 않음) */
export class LoginCancelled extends Error {}

/**
 * FS-AUTH-001·002: 소셜 인증 결과로 백엔드 로그인 → 세션 저장 → 회원 정보.
 * body 는 웹이면 인가 코드({ code, redirectUri, codeVerifier }), 앱이면 제공자 토큰({ idToken } / { accessToken })
 */
// isCancelled: 로그인 화면에서 '취소'를 눌렀는지. 취소된 시도는 토큰을 저장하지 않음 (다시 열었을 때 취소한 계정으로 로그인되지 않게)
export async function signIn(provider: Provider, body: object, isCancelled: () => boolean = () => false): Promise<MemberInfo> {
  const res = await request(`/api/v1/auth/${provider.toLowerCase()}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.message ?? `로그인 서버 오류 (${res.status})`);
  // FS-AUTH-007: 탈퇴 유예 중인 계정은 세션 없이 복구 가능 기한만 옴. 앱 로그인(제공자 토큰)이면 그 토큰으로 바로 복구할 수 있음
  if (json.accountStatus === 'WITHDRAWAL_PENDING') {
    const b = body as { idToken?: string; accessToken?: string };
    const credential = provider === 'Google' ? b.idToken : b.accessToken;
    throw new WithdrawalPending(String(json.recoverableUntil ?? '').slice(0, 10),
      credential ? cancelled => recoverAccount(provider, credential, cancelled) : undefined);
  }
  return finishLogin(provider, json, isCancelled);
}

/** 탈퇴 유예 중인 계정. recover 가 있으면 복구 가능 (웹 로그인은 제공자 토큰이 앱에 없어 복구 불가 → undefined) */
export class WithdrawalPending extends Error {
  constructor(public until: string, public recover?: (isCancelled: () => boolean) => Promise<MemberInfo>) {
    super(`탈퇴 처리 중인 계정이에요.${until ? ` ${until}까지` : ''} ${recover ? '복구할 수 있어요.' : '복구는 휴대폰 앱에서 같은 계정으로 로그인해 진행해 주세요.'}`);
  }
}
/** FS-AUTH-007 탈퇴 철회: 같은 소셜 계정의 토큰으로 복구 → 새 세션. 탈퇴 때 나간 그룹은 복구되지 않음 */
async function recoverAccount(provider: Provider, socialCredential: string, isCancelled: () => boolean): Promise<MemberInfo> {
  const res = await request('/api/v1/auth/withdrawal/cancel', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: provider.toUpperCase(), socialCredential }) });
  const json = await res.json().catch(() => ({}));
  if (res.status === 410) throw new Error('복구 기간이 지나 계정을 복구할 수 없어요.');
  if (res.status === 401) throw new Error('인증이 만료됐어요. 다시 로그인한 뒤 복구해 주세요.');
  if (!res.ok) throw new Error(json.message ?? `계정 복구 실패 (${res.status})`);
  return finishLogin(provider, json, isCancelled);
}

// 로그인·복구 응답(토큰) → 세션 저장 → 회원 정보
async function finishLogin(provider: Provider, json: any, isCancelled: () => boolean): Promise<MemberInfo> {
  if (!json.accessToken || (!WEB && !json.refreshToken)) throw new Error('로그인 응답이 올바르지 않아요. 잠시 후 다시 시도해 주세요.');
  if (isCancelled()) throw new LoginCancelled();
  await startSession(json, provider); // 토큰 저장 → 다시 열어도 로그인 유지, 만료되면 자동 갱신
  const mine = account; // 계정 번호는 토큰 갱신으로 바뀌지 않음 (gen 은 바뀜)
  // 운영 관리 권한(OPS)은 회원 정보 조회로 확인. 실패하면 로그인 응답의 값만, 권한 없음으로 둠
  const info = await fetchMember().catch(() => null);
  if (isCancelled()) {
    if (mine === account) await endSession(); // 이 시도가 만든 세션일 때만 지움 (그 사이 토큰이 갱신됐어도 지움, 새 로그인은 건드리지 않음)
    throw new LoginCancelled();
  }
  // 회원 조회 중 세션이 끊겼으면(갱신 실패로 만료) 토큰 없이 로그인된 상태가 되지 않게 실패로
  if (!session) throw new Error('로그인 세션을 확인하지 못했어요. 다시 시도해 주세요.');
  if (!info && (typeof json.memberId !== 'string' || !json.memberId)) throw new Error('로그인 응답이 올바르지 않아요. 잠시 후 다시 시도해 주세요.');
  const me = info ?? { id: json.memberId, name: typeof json.name === 'string' && json.name ? json.name : '회원', email: '', joinedAt: '', operator: false };
  // 다음 실행 때 서버에 닿지 못해도 이 회원으로 열 수 있게. 저장 전에 로그아웃·다른 로그인이 끼어들었으면 이 로그인은 실패
  if (!(await keepInfo(me, mine))) {
    if (isCancelled()) throw new LoginCancelled();
    throw new Error('로그인 세션을 확인하지 못했어요. 다시 시도해 주세요.');
  }
  return me;
}

/** 앱 시작 시: 저장된 세션이 있으면 이어서 로그인. 없거나 끝났으면 null */
// offline = 서버에 닿지 못해 저장해 둔 회원 정보로 연 상태 (화면마다 데이터 조회는 각자 실패·재시도 안내)
export async function restoreSession(): Promise<{ provider: Provider; info: MemberInfo; offline: boolean } | null> {
  const raw = await getItem(SESSION_KEY).catch(() => null);
  if (!raw) return null;
  const mine = account;
  try {
    const v = JSON.parse(raw) as Session | null;
    if (!v || typeof v.accessToken !== 'string' || (!WEB && typeof v.refreshToken !== 'string')) throw new Error('bad session');
    session = v;
  } catch {
    await endSession();
    return null;
  }
  try {
    const info = await fetchMember(); // 액세스 토큰이 만료됐으면 api() 가 갱신까지 시도
    if (!info || !session) {
      if (session) await endSession(); // 403 등 서버가 거절: 다시 로그인
      return null;
    }
    const provider = session.provider;
    if (!(await keepInfo(info, mine))) return null; // 그 사이 로그아웃됨
    return { provider, info, offline: false };
  } catch {
    // 서버 장애·네트워크 오류: 세션이 끊긴 게 아니므로 저장된 회원 정보로 엶 (재난 상황에서 앱 전체가 막히지 않게)
    const cached = session?.info;
    return session && cached && typeof cached.id === 'string' ? { provider: session.provider, info: cached, offline: true } : null;
  }
}

/** 로그아웃: 이 기기의 토큰부터 바로 지우고, 지운 토큰으로 서버 세션 폐기를 알림 (서버에 닿지 못해도 이 기기에서는 로그아웃) */
export async function logoutSession() {
  const old = session;
  const othersCookie = otherAccount(await readStored(), old); // 웹: 쿠키가 이미 다른 탭의 다른 계정 것
  await endSession();
  // refresh 토큰으로 폐기: access 토큰이 이미 만료(15분)됐어도 서버 세션이 끊김. 만료된 access 토큰을 같이 보내면 인증 필터가 401 로 막으므로 보내지 않음
  // 웹은 본문 없이 쿠키로 폐기하고, 서버가 쿠키도 지움 (쿠키가 다른 계정 것이면 그 계정을 로그아웃시키지 않게 보내지 않음)
  if (old && !othersCookie) await request('/api/v1/auth/logout', { method: 'POST', ...refreshBody(old) }).catch(() => {});
}
