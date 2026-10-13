// 백엔드 API 호출 공통 처리. 화면 코드는 fetch 를 직접 쓰지 않고 api/*.ts 의 함수를 통해 호출한다.
// 응답 형식 (API 명세서 5.2.1 공통 계약):
//   성공(2xx) → 각 API 의 응답 객체 그대로 (감싸지 않음)
//   실패      → { code, message, traceId }
// → 성공이면 본문을 그대로 돌려주고, 실패면 ApiError 를 던져 화면이 catch 로 처리하게 함
import Constants from 'expo-constants';
import { Platform } from 'react-native';

const BACKEND_PORT = 8080;

// 우선순위: EXPO_PUBLIC_API_BASE_URL → Expo 개발 서버와 같은 PC(같은 IP)의 8080 → localhost
// Expo Go 는 QR 의 PC IP(예: 192.168.0.10:8081)로 접속하므로 그 IP 를 그대로 백엔드 주소로 씀
function resolveBaseUrl() {
  const fromEnv = process.env.EXPO_PUBLIC_API_BASE_URL;
  if (fromEnv) return fromEnv.replace(/\/$/, '');
  if (Platform.OS === 'web' && typeof window !== 'undefined') return `http://${window.location.hostname}:${BACKEND_PORT}`;
  const host = Constants.expoConfig?.hostUri?.split(':')[0];
  return `http://${host ?? 'localhost'}:${BACKEND_PORT}`;
}

export const API_BASE_URL = resolveBaseUrl(); // 앱 시작 시 한 번 계산 (오류 메시지에도 표시해 연결 문제 파악용)

// code: 백엔드 ErrorCode 이름(SHELTER_NOT_FOUND 등) 또는 NETWORK / HTTP_502 — 화면에서 오류 종류별 분기에 사용
// traceId: 서버 로그를 찾을 때 쓰는 요청 추적 ID (문의·디버깅용)
export class ApiError extends Error {
  constructor(public code: string, message: string, public traceId?: string) {
    super(message);
  }
}

type ErrorBody = { code?: string; message?: string; traceId?: string };

// GET 요청. params 의 undefined·빈 값은 쿼리에서 빼고, 나머지는 URL 인코딩
// timeoutMs 안에 응답이 없으면 AbortController 로 요청을 끊음 (기본 10초)
export async function apiGet<T>(path: string, params: Record<string, string | number | undefined>, timeoutMs = 10000): Promise<T> {
  const qs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}${qs ? `?${qs}` : ''}`, { signal: ctrl.signal });
  } catch {
    // 서버 꺼짐·다른 Wi-Fi·방화벽·타임아웃
    throw new ApiError('NETWORK', `서버에 연결할 수 없어요 (${API_BASE_URL})`);
  } finally {
    clearTimeout(timer);
  }
  // 응답이 JSON 이 아닐 수도 있어(프록시 오류 페이지 등) 파싱 실패는 null 로 처리
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const err = (body ?? {}) as ErrorBody;
    throw new ApiError(err.code ?? `HTTP_${res.status}`, err.message ?? '요청을 처리하지 못했어요', err.traceId);
  }
  if (body == null) throw new ApiError(`HTTP_${res.status}`, '응답을 읽지 못했어요');
  return body as T;
}
