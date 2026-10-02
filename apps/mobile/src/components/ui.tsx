// 공통 UI 컴포넌트 + 스타일
import { ComponentProps, ReactNode } from 'react';
import { Alert, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@react-native-vector-icons/ionicons';
import { Stack } from 'expo-router';
import { KeyboardAwareScrollView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export { Ionicons as Icon };
export type IconName = ComponentProps<typeof Ionicons>['name'];

// 색상 팔레트 — 화면 전체에서 이 값만 사용
const LIGHT = {
  primary: '#2F6FED', primarySoft: '#EAF1FF', noticeText: '#1E4FB8',
  danger: '#E5484D', dangerSoft: '#FDECEC', dangerText: '#B42318',
  warning: '#F59E0B', success: '#16A34A',
  text: '#111827', sub: '#6B7280', border: '#E5E7EB', bg: '#F4F6FA', card: '#FFFFFF',
  bar: '#E9EDF3', barBorder: '#D3D9E2', // 하단 탭 바: 본문보다 한 톤 진하게 + 뚜렷한 윗선
};
const DARK: typeof LIGHT = {
  primary: '#5B8DEF', primarySoft: '#1C2A44', noticeText: '#A9C4FF',
  danger: '#F2555A', dangerSoft: '#3A1D1F', dangerText: '#FFB4AB',
  warning: '#FBBF24', success: '#22C55E',
  text: '#F3F4F6', sub: '#9CA3AF', border: '#2D3340', bg: '#0F1115', card: '#1A1D23',
  bar: '#1F232B', barBorder: '#363C48',
};

const shadow = Platform.select({
  web: { boxShadow: '0 1px 3px rgba(17,24,39,0.06)' },
  default: { shadowColor: '#111827', shadowOpacity: 0.06, shadowRadius: 6, shadowOffset: { width: 0, height: 2 }, elevation: 1 },
});

// ponytail: 테마가 바뀌면 루트 레이아웃이 applyTheme 로 C·s 를 바꿔 끼우고,
// useApp() 을 쓰는 화면들이 (theme 변경으로) 다시 그려지며 새 C·s 를 읽음.
// → 새 화면을 만들 땐 useApp() 을 한 번은 호출해야 테마가 즉시 반영됨. 화면이 많아지면 useTheme() 훅으로 교체.
export let C = LIGHT;
export let s = makeStyles(C);
export function applyTheme(dark: boolean) {
  const next = dark ? DARK : LIGHT;
  if (next !== C) { C = next; s = makeStyles(C); }
}

// Alert 의 버튼은 웹에서 동작하지 않아서 웹은 window.confirm/alert 로 대체
export function confirm(title: string, message: string, okText: string, onOk: () => void) {
  if (Platform.OS === 'web') { if (window.confirm(`${title}\n\n${message}`)) onOk(); return; }
  Alert.alert(title, message, [{ text: '취소', style: 'cancel' }, { text: okText, style: 'destructive', onPress: onOk }]);
}
export function notify(title: string, message = '') {
  if (Platform.OS === 'web') window.alert(message ? `${title}\n\n${message}` : title);
  else Alert.alert(title, message);
}

export const Card = ({ children, onPress }: { children: ReactNode; onPress?: () => void }) =>
  <Pressable onPress={onPress} disabled={!onPress} style={({ pressed }) => [s.card, pressed && s.pressed]}>{children}</Pressable>;

export const Btn = ({ title, onPress, color = C.primary, outline, icon }:
  { title: string; onPress: () => void; color?: string; outline?: boolean; icon?: IconName }) => (
  <Pressable onPress={onPress} accessibilityRole="button"
    style={({ pressed }) => [s.btn, outline ? { borderWidth: 1.5, borderColor: color, backgroundColor: C.card } : { backgroundColor: color }, pressed && s.pressed]}>
    {icon && <Ionicons name={icon} size={20} color={outline ? color : '#fff'} />}
    <Text style={[s.btnText, outline && { color }]}>{title}</Text>
  </Pressable>
);

export const Badge = ({ text, color, icon }: { text: string; color: string; icon?: IconName }) => (
  <View style={[s.badge, { backgroundColor: color + '1A' }]}>
    {icon && <Ionicons name={icon} size={12} color={color} />}
    <Text style={[s.badgeText, { color }]}>{text}</Text>
  </View>
);

export const Chip = ({ text, on, onPress }: { text: string; on: boolean; onPress: () => void }) => (
  <Pressable onPress={onPress} style={[s.chip, on && s.chipOn]} accessibilityState={{ selected: on }}>
    <Text style={[s.chipText, on && { color: '#fff' }]}>{text}</Text>
  </Pressable>
);

// 아이콘 + 제목 (카드 안 섹션 제목)
export const Label = ({ icon, text, color = C.primary }: { icon: IconName; text: string; color?: string }) => (
  <View style={[s.row, { marginBottom: 6 }]}>
    <Ionicons name={icon} size={18} color={color} />
    <Text style={s.bold}>{text}</Text>
  </View>
);

// 아이콘 + 한 줄 정보 (상세 화면의 주소·시각 등)
export const InfoRow = ({ icon, text }: { icon: IconName; text: string }) => (
  <View style={[s.row, { paddingVertical: 4 }]}>
    <Ionicons name={icon} size={16} color={C.sub} />
    <Text style={[s.body, { flex: 1 }]}>{text}</Text>
  </View>
);

// 결과가 없을 때의 빈 상태 (오류가 아닌 정상 상태로 안내)
export const Empty = ({ icon, text }: { icon: IconName; text: string }) => (
  <View style={{ alignItems: 'center', padding: 24, gap: 8 }}>
    <Ionicons name={icon} size={36} color={C.sub} />
    <Text style={[s.muted, { textAlign: 'center' }]}>{text}</Text>
  </View>
);

// 지도가 들어갈 자리
export const MapBox = ({ children }: { children: ReactNode }) => (
  <View style={s.map}>
    <Ionicons name="map-outline" size={32} color={C.primary} />
    {children}
  </View>
);

// 상세 화면 제목. 네이티브 스택 헤더(뒤로가기 버튼·스와이프 포함)에 제목만 넘김
export const Header = ({ title }: { title: string }) => <Stack.Screen options={{ title }} />;

// 스크롤 가능한 본문. 입력칸을 누르면 키보드에 가리지 않도록 자동 스크롤 (bottomOffset: 키보드와의 간격)
// keyboardShouldPersistTaps: 키보드가 열린 상태에서도 버튼이 한 번에 눌리도록
// 안드로이드는 화면이 하단 내비게이션 바 뒤까지 그려지므로(edge-to-edge) 그 높이만큼 아래 여백 추가
export function Page({ children }: { children: ReactNode }) {
  const { bottom } = useSafeAreaInsets();
  return (
    <KeyboardAwareScrollView bottomOffset={24} keyboardShouldPersistTaps="handled"
      contentContainerStyle={{ padding: 16, gap: 12, paddingBottom: 32 + bottom }}>
      {children}
    </KeyboardAwareScrollView>
  );
}

export const STALE_MIN = 10; // 이 시간(분)이 지나면 오래된 위치
export const freshness = (min?: number) =>
  min === undefined ? '위치 없음' : min <= STALE_MIN ? `${min}분 전 · 최신` : `${min}분 전 · 오래된 위치`;

function makeStyles(C: typeof LIGHT) { return StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  fill: { flex: 1 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  h1: { fontSize: 28, fontWeight: '800', color: C.text },
  h2: { fontSize: 18, fontWeight: '700', color: C.text },
  section: { fontSize: 16, fontWeight: '700', color: C.text, marginTop: 8 },
  body: { fontSize: 15, color: C.text, lineHeight: 22 },
  bold: { fontSize: 15, fontWeight: '700', color: C.text },
  muted: { color: C.sub, fontSize: 13, marginTop: 2, lineHeight: 18 },
  link: { color: C.primary, fontWeight: '600', marginLeft: 12 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  card: { backgroundColor: C.card, borderRadius: 16, padding: 16, gap: 2, ...shadow },
  pressed: { opacity: 0.7 },
  btn: { flexDirection: 'row', gap: 8, borderRadius: 14, minHeight: 52, justifyContent: 'center', alignItems: 'center', alignSelf: 'stretch' },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 16 },
  badge: { flexDirection: 'row', alignItems: 'center', gap: 3, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 999 },
  badgeText: { fontSize: 12, fontWeight: '700' },
  chip: { borderWidth: 1, borderColor: C.border, borderRadius: 999, paddingHorizontal: 14, paddingVertical: 8, backgroundColor: C.card, marginBottom: 6 },
  chipOn: { backgroundColor: C.primary, borderColor: C.primary },
  chipText: { fontSize: 14, color: C.text, fontWeight: '500' },
  input: { borderWidth: 1, borderColor: C.border, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 12, marginVertical: 10, fontSize: 15, backgroundColor: C.bg, color: C.text },
  map: { height: 160, borderRadius: 16, backgroundColor: C.primarySoft, justifyContent: 'center', alignItems: 'center', gap: 6 },
  bar: { height: 10, backgroundColor: C.border, borderRadius: 5, marginTop: 10, overflow: 'hidden' },
  barFill: { height: 10, backgroundColor: C.success, borderRadius: 5 },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: C.border, marginVertical: 10 },
  notice: { backgroundColor: C.primarySoft, color: C.noticeText, padding: 12, borderRadius: 12, textAlign: 'center' },
  noticeError: { backgroundColor: C.dangerSoft, color: C.dangerText },
}); }
