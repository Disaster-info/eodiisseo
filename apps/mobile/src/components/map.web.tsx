// TMAP 지도 (웹: iframe). react-native-webview 는 웹을 지원하지 않음
import { View } from 'react-native';
import { LatLon, MapMarker, tmapHtml } from '../data/tmap';
import { useTheme } from './ui';

// path: 경로선 좌표 (있으면 마커와 함께 화면에 맞춰 보여줌)
export function TmapView({ markers, path, label }: { markers: MapMarker[]; path?: LatLon[]; label: string }) {
  const { C, s } = useTheme();
  return (
    <View style={[s.map, { overflow: 'hidden' }]}>
      <iframe title={label} srcDoc={tmapHtml(markers, path, C.primary)} style={{ border: 0, width: '100%', height: '100%' }} />
    </View>
  );
}
