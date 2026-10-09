// 대피소 API — API 명세서 5.2.1 SHELTER
//   API-SHELTER-001 GET /api/v1/shelters/nearby?latitude&longitude  주변 대피소 (서버가 1km→5km 자동 확대)
//   API-SHELTER-002 GET /api/v1/shelters/{shelterId}                 대피소 상세
// 서버 응답은 latitude/longitude 이름을 쓰고, 앱 내부(지도 등)는 {lat, lng} 를 써서 여기서 변환한다
import { apiGet } from './client';
import type { LatLng } from './route';

// ── 서버 응답 형식 (명세 그대로) ──
type GeoPoint = { latitude: number; longitude: number };
type NearbyResponse = {
  baseLocation: GeoPoint;
  searchedRadiusKm: number; // 결과를 찾은 반경 (없으면 최대 반경 5)
  shelters: (GeoPoint & { shelterId: string; name: string; address: string; distanceMeters: number })[];
};
type DetailResponse = GeoPoint & {
  shelterId: string;
  name: string;
  address: string;
  additionalInfo: { shelterType: ShelterType; capacity: number | null; isUnderground: boolean | null };
  source: { organization: string };
  updatedAt: string | null;
};

// ── 앱에서 쓰는 형태 ──
export type ShelterType = 'EARTHQUAKE_OUTDOOR' | 'EARTHQUAKE_TEMPORARY' | 'TEMPORARY_HOUSING' | 'CIVIL_DEFENSE' | 'UNKNOWN';
// 명세의 영문 유형 코드 → 화면 표시용 한글
export const SHELTER_TYPE_LABEL: Record<ShelterType, string> = {
  EARTHQUAKE_OUTDOOR: '지진옥외대피장소',
  EARTHQUAKE_TEMPORARY: '지진겸용 임시주거시설',
  TEMPORARY_HOUSING: '이재민 임시주거시설',
  CIVIL_DEFENSE: '민방위대피시설',
  UNKNOWN: '대피소',
};

export type NearbyShelter = LatLng & { id: string; name: string; address: string; distanceMeters: number };
export type NearbyShelters = { base: LatLng; radiusKm: number; shelters: NearbyShelter[] };
// 원본에 없는 값(capacity 등)은 null — 화면에서 임의로 채우지 않음 (FR-SHELTER-013)
export type ShelterDetail = LatLng & {
  id: string; name: string; address: string;
  type: ShelterType; capacity: number | null; isUnderground: boolean | null;
  organization: string; updatedAt: string | null;
};

const toLatLng = (p: GeoPoint): LatLng => ({ lat: p.latitude, lng: p.longitude });

// 목록에서 계산된 직선거리를 상세 화면에서도 보여주기 위해 보관 (상세 API 는 거리를 주지 않음)
const distanceById = new Map<string, number>();
export const getListedDistance = (id: string) => distanceById.get(id);

/** API-SHELTER-001. base: 기준 위치(GPS 또는 지정 위치) */
export async function fetchNearbyShelters(base: LatLng): Promise<NearbyShelters> {
  const res = await apiGet<NearbyResponse>('/api/v1/shelters/nearby', { latitude: base.lat, longitude: base.lng });
  res.shelters.forEach(s => distanceById.set(s.shelterId, s.distanceMeters));
  return {
    base: toLatLng(res.baseLocation),
    radiusKm: res.searchedRadiusKm,
    shelters: res.shelters.map(s => ({ ...toLatLng(s), id: s.shelterId, name: s.name, address: s.address, distanceMeters: s.distanceMeters })),
  };
}

/** API-SHELTER-002. 서버 재시작 직후엔 주변 조회 전까지 404 일 수 있음 (DB 연동 전 메모리 보관) */
export async function fetchShelterDetail(id: string): Promise<ShelterDetail> {
  const d = await apiGet<DetailResponse>(`/api/v1/shelters/${encodeURIComponent(id)}`, {});
  return {
    ...toLatLng(d), id: d.shelterId, name: d.name, address: d.address,
    type: d.additionalInfo.shelterType, capacity: d.additionalInfo.capacity, isUnderground: d.additionalInfo.isUnderground,
    organization: d.source.organization, updatedAt: d.updatedAt,
  };
}
