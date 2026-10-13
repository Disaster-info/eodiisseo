// FS-LOC-001: 실제 GPS 현재 위치. 위치 동의(locConsent)가 있고 직접 지정 모드가 아닐 때만 켬
// expo-location: Expo Go(앱)는 기기 GPS, 웹은 브라우저 위치(localhost·HTTPS 에서만 동작)
import * as Location from 'expo-location';
import { useEffect, useState } from 'react';
import type { LatLng } from '../api/route';

// off: 사용 안 함 / loading: 확인 중 / ok: 성공 / denied: 권한 거부 / outside: 국내가 아님(에뮬레이터 기본 위치 등) / error: 실패·시간초과
export type GpsState =
  | { status: 'off' | 'loading' | 'denied' | 'outside' | 'error' }
  | { status: 'ok'; coords: LatLng };

const FRESH_MS = 60_000;   // 1분 안에 받은 위치는 다시 묻지 않고 재사용 (화면을 오갈 때마다 GPS 대기하지 않도록)
const TIMEOUT_MS = 15_000; // 실내 등에서 위치를 못 잡아 무한 대기하지 않도록
let last: { coords: LatLng; at: number } | null = null; // 화면 간 공유하는 마지막 위치

// 국외 좌표(에뮬레이터 기본 위치 등)는 outside 로 구분해 지정 위치로 대신 조회 (국내 공공데이터·TMAP 만 쓰므로 국외 좌표는 결과가 없음)
const inKorea = (p: LatLng) => p.lat >= 33 && p.lat <= 39 && p.lng >= 124 && p.lng <= 132;

export function useGps(enabled: boolean): GpsState {
  const fresh = last && Date.now() - last.at < FRESH_MS ? last.coords : null;
  const [state, setState] = useState<GpsState>(!enabled ? { status: 'off' } : fresh ? { status: 'ok', coords: fresh } : { status: 'loading' });

  useEffect(() => {
    if (!enabled) { setState({ status: 'off' }); return; }
    if (last && Date.now() - last.at < FRESH_MS) { setState({ status: 'ok', coords: last.coords }); return; }
    let alive = true; // 위치를 기다리는 중 화면을 벗어나면 결과를 버림
    setState({ status: 'loading' });
    (async () => {
      try {
        const perm = await Location.requestForegroundPermissionsAsync(); // 이미 허용했으면 팝업 없이 바로 통과
        if (!perm.granted) { if (alive) setState({ status: 'denied' }); return; }
        const pos = await Promise.race([
          Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
          new Promise<never>((_, reject) => setTimeout(() => reject(new Error('timeout')), TIMEOUT_MS)),
        ]);
        const coords = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        if (!inKorea(coords)) { if (alive) setState({ status: 'outside' }); return; }
        last = { coords, at: Date.now() };
        if (alive) setState({ status: 'ok', coords });
      } catch {
        if (alive) setState({ status: 'error' });
      }
    })();
    return () => { alive = false; };
  }, [enabled]);

  return state;
}

// GPS 를 못 쓸 때 화면에 보여줄 사유
export const GPS_FAIL_TEXT: Record<Exclude<GpsState['status'], 'ok' | 'off' | 'loading'>, string> = {
  denied: '위치 권한이 허용되지 않았어요',
  outside: '현재 위치가 국내가 아니에요',
  error: '현재 위치를 가져오지 못했어요',
};
