// TMAP 지도 그림 (웹: iframe). react-native-webview 는 웹을 지원하지 않음. 부모 크기를 꽉 채움
import { LatLon, MapMarker, tmapHtml } from '../data/tmap';
import { useTheme } from './ui';

// interactive 는 앱과 같은 모양을 위해 받음 (웹 미리보기는 map.tsx 가 손가락 동작을 막음)
export function MapCanvas({ markers, path, label }: { markers: MapMarker[]; path?: LatLon[]; label: string; interactive?: boolean }) {
  const { C } = useTheme();
  return <iframe title={label} srcDoc={tmapHtml(markers, path, C.primary)} style={{ border: 0, width: '100%', height: '100%', display: 'block' }} />;
}
