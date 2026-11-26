// 백엔드 API (develop 브랜치 backend/) 호출 + 응답을 화면 타입(types.ts)으로 변환
// 성공 응답은 감싸지 않은 DTO, 오류는 { code, message, traceId } (ErrorBody)
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { LatLon, Route, coordsOf } from './tmap';
import { Area, Disaster, DisasterType, Group, Impact, Level, Member, Shelter, Status } from './types';

/** 서버가 거절한 요청. code = 백엔드 오류 코드 (예: GROUP_FULL), status = HTTP 상태 */
export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) { super(message); }
}

// 상태별 안내: 서버 문구가 없거나(nginx 의 HTML 오류 페이지 등) 한국어가 아니면 이 문구로
// 413·429 는 프록시(nginx)가 먼저 막으면 본문이 HTML 이라 서버 문구가 없음
const STATUS_TEXT: Record<number, string> = {
  400: '입력한 내용을 확인해 주세요.',
  401: '로그인이 만료됐어요. 다시 로그인해 주세요.',
  403: '이 기능을 사용할 권한이 없어요.',
  404: '요청한 정보를 찾을 수 없어요.',
  409: '다른 변경과 겹쳤어요. 화면을 새로고침한 뒤 다시 시도해 주세요.',
  413: '보내는 내용이 너무 커요. 내용을 줄여서 다시 시도해 주세요.',
  429: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  503: '서비스를 잠시 사용할 수 없어요. 잠시 후 다시 시도해 주세요.',
};
const korean = (s: unknown): s is string => typeof s === 'string' && /[가-힣]/.test(s);
const errorText = (status: number, message: unknown) =>
  korean(message) ? message : STATUS_TEXT[status] ?? `서버 오류가 났어요. 잠시 후 다시 시도해 주세요. (${status})`;

/** 로그인이 필요한 API 를 JSON 으로. 204 는 null. 실패 상태면 서버 문구(없으면 상태별 문구)로 ApiError. ms = 응답 제한 시간 */
export async function call<T = any>(path: string, init: RequestInit = {}, ms?: number): Promise<T> {
  const res = await api(path, init.body ? { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } } : init, ms);
  if (res.status === 204) return null as T;
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(errorText(res.status, json?.message), res.status, json?.code);
  if (json === null) throw new ApiError('서버 응답이 올바르지 않아요. 잠시 후 다시 시도해 주세요.', res.status);
  return json as T;
}
const send = (method: string, body?: object) => ({ method, body: body ? JSON.stringify(body) : undefined });
const q = (o: Record<string, string | number | undefined>) =>
  new URLSearchParams(Object.entries(o).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)])).toString();

/** 오류를 사용자 문구로 */
export const errText = (e: unknown) => (e instanceof Error && e.message ? e.message : '알 수 없는 오류가 났어요.');

/** ISO 시각 → 'MM-DD HH:mm' (기기 시간대). 없거나 잘못된 값이면 '-' */
export function stamp(iso?: string | null) {
  const d = iso ? new Date(iso) : null;
  if (!d || isNaN(d.getTime())) return '-';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
const ms = (iso?: string | null) => { const t = iso ? Date.parse(iso) : NaN; return isNaN(t) ? undefined : t; };

// ── 재난 ──────────────────────────────────────────────
const TYPE: Record<string, DisasterType> = { EARTHQUAKE: '지진', HEAVY_RAIN: '호우', WILDFIRE: '산불' };
export const TYPE_CODE: Record<DisasterType, string> = { 지진: 'EARTHQUAKE', 호우: 'HEAVY_RAIN', 산불: 'WILDFIRE' };
const STATUS: Record<string, Status> = { OCCURRED: '발생', ONGOING: '진행중', ENDED: '종료', LIFTED: '해제' };
// ponytail: 백엔드 severity_level 코드표가 아직 시드되지 않아 흔한 코드만 매핑. 모르는 코드는 '공식 등급 없음' (지어내지 않음). 코드표가 확정되면 맞춰 갱신
const LEVEL: Record<string, Level> = { WARNING: '경보', ADVISORY: '주의보', WATCH: '주의보', INFO: '정보', INFORMATION: '정보' };
export const LEVEL_CODE: Record<Level, string> = { 경보: 'WARNING', 주의보: 'ADVISORY', 정보: 'INFO' };

type RawDisaster = { disasterId: string; type: string; title?: string; region?: string | null; status: string; severity?: string | null; occurredAt?: string; updatedAt?: string };
function toDisaster(r: RawDisaster): Disaster | null {
  const type = TYPE[r.type], status = STATUS[r.status];
  if (!type || !status) return null; // 앱이 모르는 유형·상태는 표시하지 않음 (잘못 분류하지 않게)
  // 서버가 영향지역을 아직 모르면(행정구역 자료 미적재 등) region 이 null → 제목으로 대신 비교하되 regionKnown=false 로 표시
  return { id: r.disasterId, type, status, title: r.title ?? '', region: r.region ?? r.title ?? '지역 정보 없음', regionKnown: !!r.region,
    level: r.severity ? LEVEL[r.severity.toUpperCase()] : undefined, occurredAt: stamp(r.occurredAt), updatedAt: stamp(r.updatedAt), sources: [] };
}

/**
 * FS-DASH-002 재난 목록. 기본값은 발생·진행 중, ended = 종료·해제.
 * 백엔드는 상태를 하나씩만 거르고(생략하면 최근 90일 종료 재난까지 섞임) 한 번에 100건까지라 상태별로 끝 페이지까지 받음.
 * 끝까지 받지 못하면 실패로 (일부만 보고 '경보 없음'으로 판단하지 않게)
 */
const PAGE = 100, MAX_PAGES = 20;
async function allPages(type: string | undefined, status: string): Promise<RawDisaster[]> {
  const out: RawDisaster[] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const rows = (await call<{ disasters: RawDisaster[] }>(`/api/v1/disasters?${q({ type, status, page, size: PAGE })}`)).disasters ?? [];
    out.push(...rows);
    if (rows.length < PAGE) return out;
  }
  throw new ApiError('재난 정보가 너무 많아 모두 받지 못했어요.', 0);
}
// 재난 목록 화면용: 상태별로 page 번째 묶음(size 개)만 받음 → 아래로 내리면 다음 묶음. more = 다음 묶음이 더 있을 수 있음
// (홈 화면은 영향 판단에 전체가 필요해 fetchDisasters 로 모두 받음)
export async function fetchDisasterPage(opt: { type?: DisasterType; ended?: boolean }, page: number, size = 20): Promise<{ items: Disaster[]; more: boolean }> {
  const type = opt.type ? TYPE_CODE[opt.type] : undefined;
  const statuses = opt.ended ? ['ENDED', 'LIFTED'] : ['OCCURRED', 'ONGOING'];
  const rows = await Promise.all(statuses.map(status =>
    call<{ disasters: RawDisaster[] }>(`/api/v1/disasters?${q({ type, status, page, size })}`).then(r => r.disasters ?? [])));
  return { items: rows.flat().map(toDisaster).filter((d): d is Disaster => !!d), more: rows.some(r => r.length === size) };
}
export async function fetchDisasters(opt: { type?: DisasterType; ended?: boolean } = {}): Promise<Disaster[]> {
  const type = opt.type ? TYPE_CODE[opt.type] : undefined;
  const statuses = opt.ended ? ['ENDED', 'LIFTED'] : ['OCCURRED', 'ONGOING'];
  const pages = await Promise.all(statuses.map(status => allPages(type, status)));
  // 상태별로 따로 받는 사이 상태가 바뀌면(발생→진행중) 같은 재난이 두 번 올 수 있어 id 로 하나만 남김
  const byId = new Map(pages.flat().map(r => [r.disasterId, r]));
  return [...byId.values()].map(toDisaster).filter((d): d is Disaster => !!d);
}

const AI: Record<string, NonNullable<Disaster['ai']>['status']> = { COMPLETED: '완료', PENDING: '대기', FAILED: '실패', STALE: '갱신 대기' };
/** FS-DASH-003 재난 상세 + FS-AISUM-002 AI 요약. 요약 조회가 실패해도 공식 정보는 보여 줌 */
export async function fetchDisaster(id: string): Promise<Disaster | null> {
  const [detail, summary] = await Promise.allSettled([
    call<RawDisaster & { officialContent?: string; source?: { organization?: string }; dataStatus?: string }>(`/api/v1/disasters/${encodeURIComponent(id)}`),
    call<{ status: string; summary?: string; generatedAt?: string }>(`/api/v1/disasters/${encodeURIComponent(id)}/ai-summary`),
  ]);
  if (detail.status === 'rejected') {
    if (detail.reason instanceof ApiError && (detail.reason.status === 404 || detail.reason.status === 400)) return null;
    throw detail.reason;
  }
  const r = detail.value, d = toDisaster(r);
  if (!d) return null;
  const s = summary.status === 'fulfilled' ? summary.value : null;
  return {
    ...d, stale: r.dataStatus === 'STALE',
    sources: r.officialContent ? [{ org: r.source?.organization ?? '출처 미상', text: r.officialContent }] : [],
    ai: s ? { status: AI[s.status] ?? '실패', text: s.summary ?? undefined, at: s.generatedAt ? stamp(s.generatedAt) : undefined } : { status: '실패' },
  };
}

// 시·도 줄임말 → 정식 이름에 들어가는 글자 (예: '경기' → '경기도', '서울' → '서울특별시')
const SIDO_FULL: Record<string, string[]> = {
  서울: ['서울'], 부산: ['부산'], 대구: ['대구'], 인천: ['인천'], 광주: ['광주광역시'], 대전: ['대전'], 울산: ['울산'], 세종: ['세종'],
  경기: ['경기'], 강원: ['강원'], 충북: ['충북', '충청북도'], 충남: ['충남', '충청남도'], 전북: ['전북', '전라북도'], 전남: ['전남', '전라남도'],
  경북: ['경북', '경상북도'], 경남: ['경남', '경상남도'], 제주: ['제주'],
};
/**
 * 재난 지역(서버: 영향지역 이름들을 ', ' 로 이은 문자열, 예: '서울특별시 강남구, 경기도 성남시')에 기준 위치('경기 성남시')가 들어 있는지.
 * 지역 이름에 시·도가 없으면 시·군·구 이름만으로 판단 → 같은 이름의 다른 지역(예: 여러 도시의 '중구')도 포함으로 봄 (경보를 놓치는 쪽보다 안전)
 * ponytail: 백엔드 목록에 영향지역 코드·판정값이 없어 이름으로 비교. 서버가 지역 코드나 relevance 를 주면 그걸로 교체
 */
export function sameRegion(disasterRegion: string, base: string) {
  const [sido, sigungu] = base.split(' ');
  if (!sido || !disasterRegion) return false;
  const sidoNames = SIDO_FULL[sido] ?? [sido];
  return disasterRegion.split(',').map(x => x.trim()).some(part => {
    const hasSido = Object.values(SIDO_FULL).flat().some(n => part.includes(n));
    const sidoOk = !hasSido || sidoNames.some(n => part.includes(n));
    return sidoOk && (sigungu ? part.includes(sigungu) : hasSido); // 세종처럼 시·군·구가 없으면 시·도만
  });
}

// ── 날씨 (FS-WEATHER-001) ─────────────────────────────
export type Weather = { temp: number | null; code: string; text: string; at: string; stale: boolean; tip?: string };
export async function fetchWeather(where: { lat: number; lon: number } | { areaId: string }): Promise<Weather> {
  const r = await call<{ temperature?: number | null; weatherCode: string; weatherText?: string; observedAt?: string; stale?: boolean; guidance?: string | null }>(
    `/api/v1/weather/current?${q('areaId' in where ? { interestAreaId: where.areaId } : { latitude: where.lat, longitude: where.lon })}`);
  return { temp: typeof r.temperature === 'number' ? r.temperature : null, code: r.weatherCode, text: r.weatherText ?? '날씨 정보 없음',
    at: stamp(r.observedAt).slice(6), stale: !!r.stale, tip: r.guidance ?? undefined };
}

// ── 대피소 (FS-SHELTER-001·002) ───────────────────────
const SHELTER_TYPE: Record<string, string> = {
  EARTHQUAKE_OUTDOOR: '지진옥외대피장소', EARTHQUAKE_TEMPORARY: '지진겸용 임시주거시설', TEMPORARY_HOUSING: '이재민 임시주거시설', CIVIL_DEFENSE: '민방위대피시설',
};
type RawShelterDetail = { shelterId: string; name: string; address: string; latitude: number; longitude: number;
  additionalInfo?: { shelterType?: string; capacity?: number | null }; source?: { organization?: string }; updatedAt?: string | null };
const detailFields = (r: RawShelterDetail) => ({
  type: r.additionalInfo?.shelterType ? SHELTER_TYPE[r.additionalInfo.shelterType] : undefined,
  capacity: r.additionalInfo?.capacity ?? undefined, org: r.source?.organization, updatedAt: r.updatedAt ? r.updatedAt.slice(0, 10) : undefined,
});
const DETAIL_MAX = 5; // ponytail: 목록 API 에 유형·수용인원이 없어 가까운 5곳만 상세 조회. 목록 API 가 주면 제거

/** 가까운 대피소: 서버가 1km 부터 넓혀 찾은 첫 반경과 직선거리순 목록 (최대 5km, 없으면 빈 목록) */
export async function fetchShelters(where: { lat: number; lon: number } | { areaId: string }): Promise<{ radius: number; list: Shelter[] }> {
  const r = await call<{ searchedRadiusKm: number; shelters: { shelterId: string; name: string; address: string; latitude: number; longitude: number; distanceMeters: number }[] }>(
    `/api/v1/shelters/nearby?${q('areaId' in where ? { interestAreaId: where.areaId } : { latitude: where.lat, longitude: where.lon })}`);
  const list: Shelter[] = (r.shelters ?? []).map(x => ({ id: x.shelterId, name: x.name, addr: x.address, lat: x.latitude, lon: x.longitude, distance: Math.round(x.distanceMeters / 100) / 10 }));
  const details = await Promise.allSettled(list.slice(0, DETAIL_MAX).map(x => call<RawShelterDetail>(`/api/v1/shelters/${x.id}`)));
  details.forEach((d, i) => { if (d.status === 'fulfilled') Object.assign(list[i], detailFields(d.value)); });
  return { radius: r.searchedRadiusKm, list };
}
/** 대피소 한 곳 (경로 화면). 없으면 null */
export async function fetchShelter(id: string): Promise<Shelter | null> {
  try {
    const r = await call<RawShelterDetail>(`/api/v1/shelters/${encodeURIComponent(id)}`);
    return { id: r.shelterId, name: r.name, addr: r.address, lat: r.latitude, lon: r.longitude, distance: NaN, ...detailFields(r) };
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) return null;
    throw e;
  }
}

// ── 안심그룹 (FS-GROUP) ───────────────────────────────
export const CATEGORY_CODE: Record<string, string> = { 가족: 'FAMILY', 친구: 'FRIEND', 기타: 'OTHER' };
const CATEGORY: Record<string, string> = { FAMILY: '가족', FRIEND: '친구', OTHER: '기타' };
const IMPACT: Record<string, Impact> = {
  INSIDE_AFFECTED_AREA: '영향지역 포함', OUTSIDE_AFFECTED_AREA: '영향지역 밖', AFFECTED_AREA_UNKNOWN: '판단 불가', LOCATION_UNAVAILABLE: '위치정보 미제공',
};
type RawLoc = { memberId: string; locationStatus: string; latitude?: number | null; longitude?: number | null; updatedAt?: string | null };

/** 그룹 하나: 정보 + 구성원 위치 + 재난 영향상태. 위치·영향 API 가 준비 안 됐으면(503 등) 확인 불가로 표시 (안전하다고 보이지 않게) */
async function fetchGroup(id: string, me: string): Promise<Group> {
  const [detail, locs, impacts] = await Promise.all([
    call<{ groupId: string; name: string; category: string; inviteCode: string; myRole: string; members: { memberId: string; name: string; role: string }[] }>(`/api/v1/groups/${id}`),
    call<{ members: RawLoc[] }>(`/api/v1/groups/${id}/members/locations`).catch(() => null),
    call<{ members: { memberId: string; status: string }[] }>(`/api/v1/groups/${id}/members/disaster-status`).catch(() => null),
  ]);
  const loc = new Map(locs?.members.map(m => [m.memberId, m]));
  const hit = new Map(impacts?.members.map(m => [m.memberId, IMPACT[m.status]]));
  const members: Member[] = detail.members.map(m => {
    const l = loc.get(m.memberId);
    const sharing = l ? l.locationStatus !== 'NOT_SHARED' : false;
    const has = l?.locationStatus === 'AVAILABLE' || l?.locationStatus === 'STALE';
    return { id: m.memberId, name: m.memberId === me ? '나' : m.name, admin: m.role === 'ADMIN', sharing,
      lat: has ? l?.latitude ?? undefined : undefined, lon: has ? l?.longitude ?? undefined : undefined, updatedAt: ms(l?.updatedAt),
      impact: hit.get(m.memberId) ?? (sharing ? '판단 불가' : '위치정보 미제공') };
  });
  return { id: detail.groupId, name: detail.name, category: CATEGORY[detail.category] ?? '기타', code: detail.inviteCode, admin: detail.myRole === 'ADMIN', members };
}
export async function fetchGroups(me: string): Promise<Group[]> {
  const { groups } = await call<{ groups: { groupId: string }[] }>('/api/v1/groups');
  return Promise.all(groups.map(g => fetchGroup(g.groupId, me)));
}
// 서버 오류 코드 → 사용자 문구 (모르는 코드는 서버 문구)
const GROUP_ERR: Record<string, string> = {
  GROUP_NOT_FOUND: '유효하지 않은 초대 코드이거나 없는 그룹이에요.', ALREADY_GROUP_MEMBER: '이미 참여 중인 그룹이에요.',
  GROUP_FULL: '그룹 인원이 가득 찼어요 (최대 10명).', GROUP_LIMIT_EXCEEDED: '그룹은 최대 3개까지 참여할 수 있어요.',
  GROUP_NOT_EMPTY: '다른 구성원이 있어 그룹을 삭제할 수 없어요.', GROUP_ADMIN_ACTION_REQUIRED: '관리자는 권한을 넘긴 뒤에 탈퇴할 수 있어요.',
  RATE_LIMITED: '요청이 너무 많아요. 1분 뒤 다시 시도해 주세요.',
};
const groupCall = <T,>(path: string, init: RequestInit) => call<T>(path, init).catch(e => {
  throw e instanceof ApiError && e.code && GROUP_ERR[e.code] ? new ApiError(GROUP_ERR[e.code], e.status, e.code) : e;
});
export const groupApi = {
  create: (name: string, category: string) => groupCall<{ inviteCode: string }>('/api/v1/groups', send('POST', { name, category: CATEGORY_CODE[category] ?? 'OTHER' })),
  join: (inviteCode: string) => groupCall<{ name: string }>('/api/v1/groups/join', send('POST', { inviteCode })),
  edit: (id: string, name: string, category: string) => groupCall(`/api/v1/groups/${id}`, send('PATCH', { name, category: CATEGORY_CODE[category] ?? 'OTHER' })),
  remove: (id: string) => groupCall(`/api/v1/groups/${id}`, send('DELETE')),
  leave: (id: string) => groupCall(`/api/v1/groups/${id}/members/me`, send('DELETE')),
  exclude: (id: string, memberId: string) => groupCall(`/api/v1/groups/${id}/members/${memberId}`, send('DELETE')),
  transfer: (id: string, memberId: string) => groupCall(`/api/v1/groups/${id}/admin`, send('PATCH', { newAdminMemberId: memberId })),
  sharing: (id: string, enabled: boolean) => groupCall(`/api/v1/groups/${id}/members/me/location-sharing`, send('PATCH', { enabled })),
};

// ── 관심지역 (FS-AREA) ────────────────────────────────
const AREA_ERR: Record<string, string> = {
  INTEREST_AREA_DUPLICATED: '이미 등록된 관심지역이에요.', INTEREST_AREA_LIMIT_EXCEEDED: '관심지역은 최대 5개까지 등록할 수 있어요.',
};
const areaCall = <T,>(path: string, init: RequestInit = {}) => call<T>(path, init).catch(e => {
  throw e instanceof ApiError && e.code && AREA_ERR[e.code] ? new ApiError(AREA_ERR[e.code], e.status, e.code) : e;
});
type RawArea = { interestAreaId: string; regionCode: string; displayName: string };
// region 은 우선 표시 이름으로 두고, 앱이 기억한 코드별 지역 이름이 있으면 AppContext 가 바꿔 끼움
const toArea = (r: RawArea): Area => ({ id: r.interestAreaId, code: r.regionCode, name: r.displayName, region: r.displayName });
export const areaApi = {
  list: async () => (await areaCall<{ interestAreas: RawArea[] }>('/api/v1/interest-areas')).interestAreas.map(toArea),
  create: async (code: string, name: string) => toArea(await areaCall<RawArea>('/api/v1/interest-areas', send('POST', { regionCode: code, displayName: name }))),
  rename: async (id: string, name: string) => toArea(await areaCall<RawArea>(`/api/v1/interest-areas/${id}`, send('PATCH', { displayName: name }))),
  move: async (id: string, code: string, name: string) => toArea(await areaCall<RawArea>(`/api/v1/interest-areas/${id}`, send('PATCH', { regionCode: code, displayName: name }))),
  remove: (id: string) => areaCall(`/api/v1/interest-areas/${id}`, send('DELETE')),
};

// ── 알림 설정 (FS-NOTI-001) ───────────────────────────
export type Noti = { on: boolean; areaIds: string[]; minLevel: Level; night: boolean };
type RawNoti = { enabled: boolean; interestAreaIds: string[]; minimumSeverity?: string | null; nightNotificationEnabled: boolean };
const toNoti = (r: RawNoti): Noti => ({ on: r.enabled, areaIds: r.interestAreaIds ?? [], minLevel: (r.minimumSeverity && LEVEL[r.minimumSeverity]) || '정보', night: r.nightNotificationEnabled });
export const notiApi = {
  get: async () => toNoti(await call<RawNoti>('/api/v1/notification-settings')),
  patch: async (n: Partial<Noti>) => toNoti(await call<RawNoti>('/api/v1/notification-settings', send('PATCH', {
    ...(n.on !== undefined && { enabled: n.on }), ...(n.areaIds && { interestAreaIds: n.areaIds }),
    ...(n.minLevel && { minimumSeverity: LEVEL_CODE[n.minLevel] }), ...(n.night !== undefined && { nightNotificationEnabled: n.night }),
  }))),
};

// ── 체크리스트 ────────────────────────────────────────
export type Checklist = { id: string; org?: string; title?: string; items: { id: string; text: string; done: boolean }[] };
export async function fetchChecklist(type: DisasterType): Promise<Checklist> {
  const r = await call<{ checklistId: string; source?: { agency?: string; reference?: string }; items: { itemId: string; content: string; completed: boolean }[] }>(
    `/api/v1/checklists?${q({ disasterType: TYPE_CODE[type] })}`);
  return { id: r.checklistId, org: r.source?.agency, title: r.source?.reference, items: r.items.map(i => ({ id: i.itemId, text: i.content, done: !!i.completed })) };
}
export const checkItem = (listId: string, itemId: string, completed: boolean) =>
  call(`/api/v1/checklists/${listId}/items/${itemId}/completion`, send('PATCH', { completed }));

// ── AI 행동요령 (FS-CHAT-001) ─────────────────────────
export const AI_QUESTION_MAX = 2000; // 백엔드 ChatRequest.MAX_LENGTH 와 같게
// 공식 자료 검색 + AI 생성이라 일반 요청(15초)보다 길게 기다림
export async function askGuidance(question: string) {
  const r = await call<{ answerType: 'AI' | 'FALLBACK'; answer: string; sources: { organization: string; title: string }[] }>('/api/v1/ai/guidance', send('POST', { question }), 60_000);
  return { text: r.answer, fallback: r.answerType !== 'AI', sources: (r.sources ?? []).map(x => `${x.organization} · ${x.title}`) };
}

// ── 회원·위치 ─────────────────────────────────────────
// 관리자인 그룹이 남아 있으면 서버가 409(GROUP_ADMIN_ACTION_REQUIRED) — 일반 문구 대신 할 일을 안내
export const withdrawMember = () => call('/api/v1/members/me', send('DELETE')).catch(e => {
  throw e instanceof ApiError && e.code === 'GROUP_ADMIN_ACTION_REQUIRED' ? new ApiError('관리자인 안심그룹이 있어 탈퇴할 수 없어요. 권한을 넘기거나 혼자인 그룹은 삭제한 뒤 다시 시도해 주세요.', e.status, e.code) : e;
});
export const putLocation = (lat: number, lon: number) => call('/api/v1/members/me/location', send('PUT', { latitude: lat, longitude: lon }));
// ── 대피소 도보 경로 (API-ROUTE-001) ─────────────────────
// TMAP 을 앱에서 직접 부르지 않고 백엔드를 거침: 서버 키로 호출 + 회원당 분당 10회 제한 (앱 번들의 키로 경로 API 를 남용하지 못하게)
export async function fetchShelterRoute(shelterId: string, from: LatLon): Promise<Route> {
  const r = await call<{ distanceMeters?: number; estimatedDurationSeconds?: number; routePath?: { latitude: number; longitude: number }[] }>(
    `/api/v1/routes/shelters/${encodeURIComponent(shelterId)}?originLatitude=${from.lat}&originLongitude=${from.lon}`);
  // 거리·시간·경로선 중 하나라도 없으면 가짜 결과 대신 실패로 (화면이 다시 시도 안내)
  if (typeof r.distanceMeters !== 'number' || typeof r.estimatedDurationSeconds !== 'number' || !r.routePath?.length) throw new Error('경로 응답이 불완전해요');
  return { meters: r.distanceMeters, seconds: r.estimatedDurationSeconds, path: r.routePath.map(p => ({ lat: p.latitude, lon: p.longitude })) };
}

export const getConsent = () => call<{ consented: boolean }>('/api/v1/members/me/location-consent');
export const patchConsent = (consented: boolean) => call('/api/v1/members/me/location-consent', send('PATCH', { consented }));

// ── 운영 (FS-OPS) ─────────────────────────────────────
export const opsApi = {
  status: () => call<{ mockApiEnabled: boolean; apiStatuses: { apiName: string; status: string; lastSuccessfulCollectedAt?: string | null }[] }>('/api/v1/ops/status'),
  errors: () => call<{ errorLogs: { occurredAt?: string; source?: string; errorType?: string; message?: string }[] }>('/api/v1/ops/error-logs?size=50'),
  disasters: (type?: DisasterType, status?: string) =>
    call<{ disasters: (RawDisaster & { collectedAt?: string })[] }>(`/api/v1/ops/disasters?${q({ type: type && TYPE_CODE[type], status, size: 50 })}`)
      .then(r => (r.disasters ?? []).map(toDisaster).filter((d): d is Disaster => !!d)),
  recollect: (source: string) => call('/api/v1/ops/collections', send('POST', { source })),
};

/**
 * 화면에서 비동기 조회: { data, error, loading, reload }. key 가 바뀌면 다시 조회하고, 늦게 온 이전 결과는 버림
 * key = null 이면 조회하지 않음 (조회 조건이 아직 없을 때)
 */
export function useLoad<T>(key: string | null, load: () => Promise<T>) {
  const [state, setState] = useState<{ key: string; data?: T; error?: string } | null>(null);
  const loadRef = useRef(load);
  useEffect(() => { loadRef.current = load; });
  const seq = useRef(0); // 가장 최근 요청 번호: 화면을 떠났거나 더 새 요청이 있으면 결과를 버림
  const run = useCallback((k: string) => {
    const my = ++seq.current;
    return loadRef.current().then(data => { if (my === seq.current) setState({ key: k, data }); },
      e => { if (my === seq.current) setState({ key: k, error: errText(e) }); });
  }, []);
  useEffect(() => {
    if (key !== null) run(key);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 일부러 지금 번호를 올려 진행 중인 요청을 무효화함
    return () => { seq.current++; };
  }, [key, run]);
  /** 같은 조건으로 다시 조회 (끝나면 resolve). 다시 받는 동안 이전 결과를 그대로 보여 줌 */
  const reload = useCallback(() => (key === null ? Promise.resolve() : run(key)), [key, run]);
  const mine = state?.key === key ? state : null; // 다른 조건의 결과는 보여 주지 않음
  return { data: mine?.data, error: mine?.error, loading: key !== null && !mine, reload };
}

/**
 * 기준 위치 → 좌표·가까운 대피소. 기준 = 관심지역(서버가 중심 좌표 사용) 또는 좌표(GPS·고른 장소) 또는 지역 이름(TMAP 으로 대표 좌표)
 * target = null 이면 조회하지 않음 (기준 위치를 아직 모름)
 */
export type Base = { areaId: string } | { pos: LatLon | null; region: string };
export function useShelters(target: Base | null) {
  const key = !target ? null : 'areaId' in target ? `a:${target.areaId}` : target.pos ? `p:${target.pos.lat},${target.pos.lon}` : `r:${target.region}`;
  return useLoad(key, async () => {
    if (!target) throw new Error('기준 위치 없음');
    if ('areaId' in target) return { from: null, ...(await fetchShelters({ areaId: target.areaId })) };
    const from = target.pos ?? (await coordsOf(target.region));
    return { from, ...(await fetchShelters(from)) };
  });
}
