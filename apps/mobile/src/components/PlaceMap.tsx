// 지도: 지점 마커 + (선택) 경로선. iOS: Apple 지도, Android: Google 지도 — Expo Go 기본 내장
// 웹은 react-native-maps 를 쓸 수 없어 PlaceMap.web.tsx 가 대신 쓰임
//   → Metro 번들러가 플랫폼별 확장자를 자동 선택: 웹 빌드는 .web.tsx, 앱 빌드는 이 파일
//   → 화면에서는 import PlaceMap from '.../PlaceMap' 한 줄이면 됨
// 사용 예) 대피소 탭: 기준 위치(start) + 번호 마커(place) 여러 개, 탭하면 선택
//         대피소 상세: pins 1개 / 대피 경로: pins 2개(출발·도착) + path(경로선)
import { useEffect, useRef } from 'react';
import { Text, View } from 'react-native';
import MapView, { Marker, Polyline } from 'react-native-maps';
import { C } from './ui';
import type { LatLng } from '../api/route';

// kind: start 초록 핀(출발·기준 위치) / end 빨강 핀(도착) / place 번호 원(목록 항목, label 에 번호)
export type MapPin = LatLng & { title: string; kind: 'start' | 'end' | 'place'; label?: string };
export type PlaceMapProps = {
  pins: MapPin[];
  path?: LatLng[];
  height?: number;
  selected?: number | null;           // 강조할 pins 의 인덱스 (목록에서 고른 항목)
  onPinPress?: (index: number) => void; // 마커를 눌렀을 때 (목록 선택과 연동)
};

// 앱 공통 좌표 {lat, lng} → react-native-maps 형식 {latitude, longitude}
const toMap = (p: LatLng) => ({ latitude: p.lat, longitude: p.lng });
const PIN_COLOR = { start: 'green', end: 'red' } as const;
const PADDING = { top: 40, right: 40, bottom: 40, left: 40 };

export default function PlaceMap({ pins, path, height = 280, selected, onPinPress }: PlaceMapProps) {
  const ref = useRef<MapView>(null);
  const ready = useRef(false);
  const all = [...(path ?? []), ...pins].map(toMap);
  const fitKey = JSON.stringify(all); // 지점 구성이 바뀔 때만 다시 맞춤 (선택만 바뀌면 화면 유지)

  // 지점이 둘 이상이면 전체가 보이도록 맞춤, 하나면 그 위치로 이동 (기준 위치 칩을 바꿨을 때도 따라가도록)
  const fit = () => {
    if (all.length > 1) ref.current?.fitToCoordinates(all, { edgePadding: PADDING, animated: ready.current });
    else if (all.length === 1) ref.current?.animateToRegion({ ...all[0], latitudeDelta: 0.01, longitudeDelta: 0.01 }, ready.current ? 300 : 0);
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { if (ready.current) fit(); }, [fitKey]);

  return (
    <MapView
      ref={ref}
      style={{ height, borderRadius: 16 }}
      initialRegion={{ ...toMap(pins[0]), latitudeDelta: 0.01, longitudeDelta: 0.01 }}
      onMapReady={() => { fit(); ready.current = true; }}
      toolbarEnabled={false}
    >
      {path && <Polyline coordinates={path.map(toMap)} strokeColor={C.primary} strokeWidth={5} />}
      {pins.map((p, i) => p.kind === 'place' ? (
        // 번호 원 마커. key 에 선택 여부를 넣어 선택이 바뀌면 다시 그려지게 함 (Android 커스텀 마커 갱신 문제 회피)
        <Marker key={`${i}-${selected === i}`} coordinate={toMap(p)} title={p.title} onPress={() => onPinPress?.(i)}
          zIndex={selected === i ? 2 : 1}>
          <View style={{ width: 28, height: 28, borderRadius: 14, justifyContent: 'center', alignItems: 'center', borderWidth: 2,
            backgroundColor: selected === i ? C.danger : '#fff', borderColor: selected === i ? C.danger : C.primary }}>
            <Text style={{ fontSize: 12, fontWeight: '800', color: selected === i ? '#fff' : C.primary }}>{p.label}</Text>
          </View>
        </Marker>
      ) : (
        <Marker key={i} coordinate={toMap(p)} title={p.title} pinColor={PIN_COLOR[p.kind]} onPress={() => onPinPress?.(i)} />
      ))}
    </MapView>
  );
}
