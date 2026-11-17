// TMAP 지도 그림 (iOS·Android: WebView). 부모 크기를 꽉 채움. 웹은 mapCanvas.web.tsx, 틀·미리보기·전체 화면은 map.tsx
// ponytail: 지도 SDK 교체(카카오 등)는 앱·웹을 함께 바꿀 때 이 파일과 mapCanvas.web.tsx 만 바꾸면 됨
import { WebView } from 'react-native-webview';
import { LatLon, MapMarker, tmapHtml } from '../data/tmap';
import { useTheme } from './ui';

// interactive 는 웹과 같은 모양을 위해 받음 (미리보기의 손가락 동작은 map.tsx 가 막음)
export function MapCanvas({ markers, path }: { markers: MapMarker[]; path?: LatLon[]; label: string; interactive?: boolean }) {
  const { C } = useTheme();
  return (
    <WebView source={{ html: tmapHtml(markers, path, C.primary), baseUrl: 'https://localhost' }} originWhitelist={['*']}
      // 부모가 alignItems: 'center' 여도 너비 0 이 되지 않게 (iOS 빈 화면 방지)
      containerStyle={{ width: '100%', flex: 1 }} />
  );
}
