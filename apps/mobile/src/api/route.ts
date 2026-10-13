// 대피 경로 API — API 명세서 5.2.1 API-ROUTE-001
//   GET /api/v1/routes/shelters/{shelterId}?originLatitude&originLongitude
// 앱은 TMAP 을 직접 부르지 않고 백엔드를 거친다 (TMAP 키가 앱 번들에 들어가지 않도록)
// 서버 응답은 latitude/longitude, 앱 내부(지도 등)는 {lat, lng} 를 써서 여기서 변환한다
import { apiGet } from './client';

export type LatLng = { lat: number; lng: number };

// ── 서버 응답 형식 (명세 그대로) ──
type GeoPoint = { latitude: number; longitude: number };
type RouteResponse = {
  origin: GeoPoint;
  destination: GeoPoint & { shelterId: string; name: string };
  distanceMeters: number | null;           // 제공자 미제공 시 null
  estimatedDurationSeconds: number | null; // 제공자 미제공 시 null
  routePath: GeoPoint[];
  routeSegments: GeoPoint[][];
  provider: string;
  coordinateSystem: string;
  guides: (GeoPoint & { description: string; pointType: string; turnType: number | null })[]; // 명세 추가 검토 필드
};

// ── 앱에서 쓰는 형태 ──
export type ShelterRoute = {
  origin: LatLng;
  destination: LatLng & { id: string; name: string };
  distanceMeters: number | null;
  durationSeconds: number | null;
  path: LatLng[];        // 지도에 그릴 경로선
  guides: (LatLng & { description: string; pointType: string })[];
};

const toLatLng = (p: GeoPoint): LatLng => ({ lat: p.latitude, lng: p.longitude });

// shelterId: 도착 대피소, origin: 출발 좌표 (GPS 또는 지정 위치)
export async function fetchShelterRoute(shelterId: string, origin: LatLng): Promise<ShelterRoute> {
  const r = await apiGet<RouteResponse>(`/api/v1/routes/shelters/${encodeURIComponent(shelterId)}`, {
    originLatitude: origin.lat, originLongitude: origin.lng,
  });
  return {
    origin: toLatLng(r.origin),
    destination: { ...toLatLng(r.destination), id: r.destination.shelterId, name: r.destination.name },
    distanceMeters: r.distanceMeters,
    durationSeconds: r.estimatedDurationSeconds,
    path: r.routePath.map(toLatLng),
    guides: (r.guides ?? []).map(g => ({ ...toLatLng(g), description: g.description, pointType: g.pointType })),
  };
}
