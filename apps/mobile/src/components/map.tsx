// TMAP 지도 (iOS·Android: WebView). 웹은 map.web.tsx
import { View } from 'react-native';
import { WebView } from 'react-native-webview';
import { LatLon, MapMarker, tmapHtml } from '../data/tmap';
import { useTheme } from './ui';

// path: 경로선 좌표 (있으면 마커와 함께 화면에 맞춰 보여줌)
export function TmapView({ markers, path, label }: { markers: MapMarker[]; path?: LatLon[]; label: string }) {
  const { C, s } = useTheme();
  return (
    <View style={[s.map, { overflow: 'hidden' }]} accessible accessibilityLabel={label}>
      <WebView source={{ html: tmapHtml(markers, path, C.primary), baseUrl: 'https://localhost' }} originWhitelist={['*']}
        // s.map 이 alignItems: 'center' 라 너비를 직접 주지 않으면 iOS 에서 WebView 너비가 0 (빈 화면)
        containerStyle={{ width: '100%' }} />
    </View>
  );
}
