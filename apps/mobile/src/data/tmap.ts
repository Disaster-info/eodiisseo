// TMAP (SK open API) — 장소 검색·도보 경로·주소↔좌표 변환·지도 HTML (앱 키: .env.local 의 EXPO_PUBLIC_TMAP_APP_KEY)
const KEY = process.env.EXPO_PUBLIC_TMAP_APP_KEY ?? '';
// REST 호출 공통: 앱 키 헤더 + JSON 요청(Accept 가 없으면 일부 API 가 빈 응답). body 가 있으면 POST. 실패 상태면 Error
async function tmap(path: string, body?: object) {
  const headers = { Accept: 'application/json', appKey: KEY };
  const res = await fetch(`https://apis.openapi.sk.com/tmap/${path}`, body
    ? { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
    : { headers });
  if (!res.ok) throw new Error(`TMAP ${res.status}`);
  return res;
}

// 장소(POI) 통합 검색
export type Poi = { id: string; name: string; addr: string; region: string; lat: number; lon: number };

type RawPoi = { id: string; name: string; upperAddrName: string; middleAddrName: string; lowerAddrName: string; frontLat: string; frontLon: string };

export async function searchPois(keyword: string): Promise<Poi[]> {
  const q = new URLSearchParams({ version: '1', searchKeyword: keyword, searchType: 'all', page: '1', count: '20',
    resCoordType: 'WGS84GEO', reqCoordType: 'WGS84GEO', multiPoint: 'N', searchtypCd: 'A', poiGroupYn: 'N' });
  const res = await tmap(`pois?${q}`);
  if (res.status === 204) return []; // 검색 결과 없음 (본문 없이 옴)
  const json = await res.json();
  return (json.searchPoiInfo.pois.poi as RawPoi[]).map(p => ({
    id: p.id, name: p.name,
    addr: [p.upperAddrName, p.middleAddrName, p.lowerAddrName].filter(Boolean).join(' '),
    // 앱의 기준 위치 형식(시·도 + 시·군·구)에 맞춤: '수원시 팔달구' → '경기 수원시'
    region: `${p.upperAddrName} ${p.middleAddrName.split(' ')[0]}`,
    lat: Number(p.frontLat), lon: Number(p.frontLon),
  }));
}

// TMAP 보행자 경로안내 — 도보 기본 경로 그대로 사용 (FS-ROUTE-001)
export type LatLon = { lat: number; lon: number };
export type Place = LatLon & { name: string }; // 장소 검색으로 고른 출발점
export type Route = { meters: number; seconds: number; path: LatLon[] };

/** 두 좌표 사이 직선거리(km) — 하버사인 공식 */
export function distanceKm(a: LatLon, b: LatLon) {
  const rad = (d: number) => (d * Math.PI) / 180;
  const h = Math.sin(rad(b.lat - a.lat) / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(rad(b.lon - a.lon) / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}
type Feature = { geometry: { type: string; coordinates: number[][] }; properties: { totalDistance?: number; totalTime?: number } };

export async function searchRoute(from: LatLon, to: LatLon): Promise<Route> {
  const res = await tmap('routes/pedestrian?version=1', { startX: from.lon, startY: from.lat, endX: to.lon, endY: to.lat,
    startName: '출발', endName: '도착', reqCoordType: 'WGS84GEO', resCoordType: 'WGS84GEO' });
  const { features } = (await res.json()) as { features: Feature[] };
  return {
    meters: features[0].properties.totalDistance ?? 0,
    seconds: features[0].properties.totalTime ?? 0,
    // 구간(LineString)들을 이어 붙여 경로선 하나로. TMAP 좌표 순서는 [경도, 위도]
    path: features.filter(f => f.geometry.type === 'LineString').flatMap(f => f.geometry.coordinates.map(([lon, lat]) => ({ lat, lon }))),
  };
}

// TMAP 주소 지오코딩: '서울 강남구' 같은 지역 이름 → 대표 좌표 (구청·시청 부근). 지역만 고른 지정 위치의 경로 출발점
export async function coordsOf(addr: string): Promise<LatLon> {
  const q = new URLSearchParams({ version: '1', fullAddr: addr, coordType: 'WGS84GEO' });
  const res = await tmap(`geo/fullAddrGeo?${q}`);
  const c = (await res.json()).coordinateInfo.coordinate[0] as { lat: string; lon: string; newLat: string; newLon: string };
  return { lat: Number(c.lat || c.newLat), lon: Number(c.lon || c.newLon) }; // 지번 좌표가 없으면 도로명 좌표
}

// TMAP 리버스 지오코딩: 좌표 → 앱의 기준 위치 형식 '서울 강남구'. 시·도는 전체 이름('서울특별시')으로 와서 줄임
const SIDO: Record<string, string> = {
  서울특별시: '서울', 부산광역시: '부산', 대구광역시: '대구', 인천광역시: '인천', 광주광역시: '광주', 대전광역시: '대전', 울산광역시: '울산',
  세종특별자치시: '세종', 경기도: '경기', 강원특별자치도: '강원', 강원도: '강원', 충청북도: '충북', 충청남도: '충남',
  전북특별자치도: '전북', 전라북도: '전북', 전라남도: '전남', 경상북도: '경북', 경상남도: '경남', 제주특별자치도: '제주',
};
export async function regionOf({ lat, lon }: LatLon): Promise<string> {
  const q = new URLSearchParams({ version: '1', lat: String(lat), lon: String(lon), coordType: 'WGS84GEO', addressType: 'A10' });
  const res = await tmap(`geo/reversegeocoding?${q}`);
  const { city_do, gu_gun } = (await res.json()).addressInfo as { city_do: string; gu_gun: string };
  return `${SIDO[city_do] ?? city_do} ${gu_gun.split(' ')[0]}`; // '수원시 팔달구' → '수원시' (POI 검색과 같은 규칙)
}

// TMAP 지도(JavaScript SDK v2)를 띄우는 HTML. 앱은 WebView, 웹은 iframe 으로 같은 HTML 을 그림
// SDK 로더가 document.write 로 본체를 불러오므로 <script> 태그로 문서 안에 직접 넣어야 함
export type MapMarker = { lat: number; lon: number; title: string; me?: boolean }; // me = 내 위치 (점 아이콘으로 구분)
export function tmapHtml(markers: MapMarker[], path: LatLon[] = [], lineColor = '#000', zoom = 16) {
  const data = JSON.stringify(markers).replace(/</g, '\\u003c'); // 이름에 </script> 가 있어도 안전하게
  return `<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>html,body,#map{margin:0;width:100%;height:100%}</style>
<script src="https://apis.openapi.sk.com/tmap/jsv2?version=1&appKey=${KEY}"></script>
</head><body><div id="map"></div><script>
var markers = ${data};
var path = ${JSON.stringify(path)};
var map = new Tmapv2.Map('map', { center: new Tmapv2.LatLng(markers[0].lat, markers[0].lon), width: '100%', height: '100%', zoom: ${zoom} });
var ME_ICON = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22"><circle cx="11" cy="11" r="8" fill="${lineColor}" stroke="white" stroke-width="3"/></svg>');
var bounds = new Tmapv2.LatLngBounds();
markers.forEach(function (m) {
  var p = new Tmapv2.LatLng(m.lat, m.lon);
  new Tmapv2.Marker(m.me ? { position: p, map: map, title: m.title, icon: ME_ICON, iconSize: new Tmapv2.Size(22, 22) } : { position: p, map: map, title: m.title });
  bounds.extend(p);
});
if (path.length) {
  var line = path.map(function (p) { return new Tmapv2.LatLng(p.lat, p.lon); });
  new Tmapv2.Polyline({ path: line, strokeColor: '${lineColor}', strokeWeight: 6, map: map });
  line.forEach(function (p) { bounds.extend(p); });
}
if (markers.length > 1 || path.length) map.fitBounds(bounds); // 마커·경로가 모두 보이게
</script></body></html>`;
}
