// 지도 (웹): react-native-maps 가 웹을 지원하지 않아 Leaflet + OpenStreetMap 으로 표시
// Leaflet 은 CDN 에서 한 번만 불러옴 (npm 의존성·CSS 번들 설정 없이)
// props 는 앱용 PlaceMap 과 같아서 화면 코드는 플랫폼을 신경 쓰지 않아도 됨
import { useEffect, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import { C, s } from './ui';
import type { PlaceMapProps } from './PlaceMap';

const LEAFLET = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet';
let loading: Promise<any> | null = null; // 여러 지도가 동시에 떠도 스크립트는 한 번만 받도록 공유

// <link>(CSS)·<script>(JS)를 head 에 붙이고, 로드가 끝나면 전역 window.L(Leaflet)을 돌려줌
function loadLeaflet(): Promise<any> {
  const w = window as any;
  if (w.L) return Promise.resolve(w.L);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = `${LEAFLET}.css`;
      document.head.appendChild(css);
      const js = document.createElement('script');
      js.src = `${LEAFLET}.js`;
      js.onload = () => resolve(w.L);
      js.onerror = () => { loading = null; reject(new Error('leaflet load failed')); };
      document.head.appendChild(js);
    });
  }
  return loading;
}

// 번호 원 마커 HTML (앱의 place 마커와 같은 모양)
const placeIcon = (L: any, label: string, on: boolean) => L.divIcon({
  className: '',
  iconSize: [28, 28],
  html: `<div style="width:28px;height:28px;border-radius:14px;display:flex;align-items:center;justify-content:center;
    box-sizing:border-box;border:2px solid ${on ? C.danger : C.primary};background:${on ? C.danger : '#fff'};
    color:${on ? '#fff' : C.primary};font:800 12px sans-serif">${label}</div>`,
});

export default function PlaceMap({ pins, path, height = 280, selected, onPinPress }: PlaceMapProps) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<{ L: any; map: any; layer: any } | null>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const pressRef = useRef(onPinPress);
  pressRef.current = onPinPress; // 마커 클릭 시 항상 최신 콜백을 부르도록
  // 호출부가 매 렌더 새 배열을 넘겨도 내용이 같으면 다시 그리지 않음
  const pinsKey = JSON.stringify(pins);
  const pathKey = path ? `${path.length}:${JSON.stringify(path[0])}:${JSON.stringify(path[path.length - 1])}` : '';

  // 1) 지도 생성 — 화면에 붙을 때 한 번
  useEffect(() => {
    let cancelled = false; // Leaflet 로딩 중 화면을 벗어난 경우
    loadLeaflet().then(L => {
      if (cancelled || !box.current) return;
      const map = L.map(box.current); // 아래 <div> 안에 지도를 그림
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19, attribution: '&copy; OpenStreetMap contributors',
      }).addTo(map);
      mapRef.current = { L, map, layer: L.layerGroup().addTo(map) }; // 마커·경로선은 layer 에 모아 한 번에 지우고 다시 그림
      setReady(true);
    }).catch(() => setFailed(true));
    return () => { cancelled = true; mapRef.current?.map.remove(); mapRef.current = null; };
  }, []);

  // 2) 마커·경로선 그리기 — 지점·경로·선택이 바뀔 때
  useEffect(() => {
    const m = mapRef.current;
    if (!ready || !m) return;
    const { L, layer } = m;
    layer.clearLayers();
    if (path) L.polyline(path.map(p => [p.lat, p.lng]), { color: C.primary, weight: 5 }).addTo(layer);
    pins.forEach((p, i) => {
      const marker = p.kind === 'place'
        ? L.marker([p.lat, p.lng], { icon: placeIcon(L, p.label ?? '', selected === i), zIndexOffset: selected === i ? 1000 : 0 })
        : L.circleMarker([p.lat, p.lng], { radius: 8, color: '#fff', weight: 2, fillOpacity: 1, fillColor: p.kind === 'start' ? C.success : C.danger });
      marker.addTo(layer).bindTooltip(p.title).on('click', () => pressRef.current?.(i));
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, pinsKey, pathKey, selected]);

  // 3) 화면 맞춤 — 지점·경로 구성이 바뀔 때만 (선택만 바뀌면 보던 위치 유지)
  useEffect(() => {
    const m = mapRef.current;
    if (!ready || !m) return;
    const all = [...(path ?? []), ...pins].map(p => [p.lat, p.lng]);
    if (all.length > 1) m.map.fitBounds(all, { padding: [30, 30] });
    else if (all.length === 1) m.map.setView(all[0], 16); // 지점이 하나면 그 위치를 확대
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, pinsKey, pathKey]);

  if (failed) return <View style={s.map}><Text style={s.muted}>지도를 불러오지 못했어요.</Text></View>;
  return (
    <View style={{ height, borderRadius: 16, overflow: 'hidden', backgroundColor: C.primarySoft }}>
      <div ref={box} style={{ width: '100%', height: '100%' }} />
    </View>
  );
}
