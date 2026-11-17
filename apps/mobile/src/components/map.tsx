// TMAP 지도 틀 (iOS·Android·웹 공통): 높이 + '크게 보기' 버튼 + 전체 화면 지도
// 화면 안 지도는 미리보기(움직이지 않음), 누르면 전체 화면에서 확대·이동 — 스크롤 화면 안 지도의 손가락 동작 겹침을 없앰
import { memo, useState } from 'react';
import { Modal, Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { LatLon, MapMarker } from '../data/tmap';
import { MapCanvas } from './mapCanvas';
import { Icon, useTheme } from './ui';

// path: 경로선 좌표 (있으면 마커와 함께 화면에 맞춰 보여줌). height: 화면 안 지도 높이 (기본 160)
type Props = { markers: MapMarker[]; path?: LatLon[]; label: string; height?: number };

function TmapViewInner({ markers, path, label, height = 160 }: Props) {
  const { C, s } = useTheme();
  const { top, bottom } = useSafeAreaInsets();
  const [full, setFull] = useState(false);
  return (
    <>
      {/* 미리보기: 손가락 동작을 받지 않음(화면 스크롤과 겹치지 않게) → 지도 어디를 눌러도 크게 보기 */}
      <Pressable onPress={() => setFull(true)} accessibilityRole="button" accessibilityLabel={`${label}. 눌러서 크게 보기`}
        style={({ pressed }) => [s.map, { height, overflow: 'hidden', alignItems: 'stretch', justifyContent: 'flex-start' }, pressed && s.pressed]}>
        <View style={{ flex: 1, pointerEvents: 'none' }}>
          <MapCanvas markers={markers} path={path} label={label} interactive={false} />
        </View>
        <View style={[s.row, { position: 'absolute', top: 8, left: 8, gap: 4, minHeight: 36, paddingHorizontal: 12, borderRadius: 999,
          backgroundColor: C.card, borderWidth: 1, borderColor: C.border, pointerEvents: 'none' }]}>
          <Icon name="expand-outline" size={18} color={C.primary} />
          <Text style={[s.small, { color: C.primary, fontWeight: '700' }]}>크게 보기</Text>
        </View>
      </Pressable>

      {/* 전체 화면 지도: 닫기 버튼 또는 Android 뒤로가기로 닫힘 */}
      <Modal visible={full} animationType="slide" presentationStyle="fullScreen" onRequestClose={() => setFull(false)} statusBarTranslucent>
        <View style={{ flex: 1, backgroundColor: C.bg, paddingTop: top, paddingBottom: bottom }}>
          <View style={[s.row, { minHeight: 56, paddingHorizontal: 8, gap: 4, backgroundColor: C.card, borderBottomWidth: 1, borderBottomColor: C.border }]}>
            <Text style={[s.bold, { flex: 1, marginLeft: 8 }]} numberOfLines={1} accessibilityRole="header">{label}</Text>
            <Pressable onPress={() => setFull(false)} accessibilityRole="button" accessibilityLabel="지도 닫기" hitSlop={8}
              style={({ pressed }) => [{ width: 44, height: 44, borderRadius: 22, justifyContent: 'center', alignItems: 'center' }, pressed && s.pressed]}>
              <Icon name="close" size={26} color={C.text} />
            </Pressable>
          </View>
          <View style={{ flex: 1 }}>
            {full && <MapCanvas markers={markers} path={path} label={label} interactive />}
          </View>
        </View>
      </Modal>
    </>
  );
}

// 화면이 다시 그려져도 지도 내용(마커·경로·제목·높이)이 같으면 지도(WebView·iframe)를 다시 그리지 않음
// 마커 배열은 부르는 화면에서 매번 새로 만들어지므로 내용으로 비교 (마커 수가 적어 비교 비용은 작음)
// 경로(path)는 경로 결과 객체가 그대로 유지되므로 참조만 비교
export const TmapView = memo(TmapViewInner, (a, b) =>
  a.label === b.label && a.height === b.height && a.path === b.path && JSON.stringify(a.markers) === JSON.stringify(b.markers));
