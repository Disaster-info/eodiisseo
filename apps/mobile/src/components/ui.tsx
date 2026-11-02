// 공통 UI 컴포넌트 + 스타일
import { ComponentProps, ReactNode, createContext, useContext, useState } from 'react';
import { ActionSheetIOS, Alert, Modal, Platform, Pressable, PressableProps, ScrollViewProps, StyleSheet, Switch, SwitchProps, Text, View } from 'react-native';
import { Ionicons } from '@react-native-vector-icons/ionicons';
import { Stack } from 'expo-router';
import { KeyboardAwareScrollView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { Impact, Level } from '../data/mock';

export { Ionicons as Icon };
export type IconName = ComponentProps<typeof Ionicons>['name'];

// 도로표지 팔레트 — 바탕은 중립 회색, 색은 표지판(상태·대피 안내)에만.
// 표지판 색(sign)은 낮·밤 모두 같은 색 (실제 표지판처럼). 글자용 색(primary·danger 등)은 바탕 대비 4.5:1 이상
const SIGN = { blue: '#0B5CAD', green: '#0E7A4F', red: '#D7261E', yellow: '#F5C400' };
const LIGHT = {
  primary: '#0B5CAD', fill: '#0B5CAD', primarySoft: '#E3EDF8', noticeText: '#0A4C8F', // primary: 글자·아이콘, fill: 흰 글자를 얹는 버튼 바탕
  danger: '#B3201A', dangerSoft: '#FBE6E4', dangerText: '#B3201A',
  warning: '#8A5A00', success: '#0E7A4F',
  text: '#16181B', sub: '#5C6168', border: '#D9DCD8', bg: '#F2F3F1', card: '#FFFFFF',
  bar: '#FFFFFF', barBorder: '#D9DCD8',
  sign: SIGN, onSign: '#FFFFFF', onYellow: '#16181B',
};
const DARK: typeof LIGHT = {
  primary: '#7DB2EC', fill: '#1F67B5', primarySoft: '#16263A', noticeText: '#A9CBF2',
  danger: '#FF8F85', dangerSoft: '#3A1A18', dangerText: '#FFB0A8',
  warning: '#F2C230', success: '#5FC89A',
  text: '#EDEEEC', sub: '#A3A8AE', border: '#30353A', bg: '#121416', card: '#1C1F22',
  bar: '#1C1F22', barBorder: '#30353A',
  sign: SIGN, onSign: '#FFFFFF', onYellow: '#16181B',
};
export type Colors = typeof LIGHT;

// 위험수준 = 도로표지 종류: 정보는 청색 안내, 주의보는 노란 주의표지, 경보는 빨간 규제표지 (색 + 아이콘 모양 + 글자)
export const levelFill = (C: Colors, l: Level) => ({ 경보: C.sign.red, 주의보: C.sign.yellow, 정보: C.sign.blue })[l];
export const levelInk = (C: Colors, l: Level) => (l === '주의보' ? C.onYellow : C.onSign);
export const impactColor = (C: Colors, i: Impact) =>
  i === '영향지역 포함' ? C.danger : i === '영향지역 밖' ? C.success : C.sub;

const shadow = Platform.select({
  web: { boxShadow: '0 1px 3px rgba(22,24,27,0.08)' },
  default: { shadowColor: '#16181B', shadowOpacity: 0.08, shadowRadius: 6, shadowOffset: { width: 0, height: 2 }, elevation: 1 },
});

// 테마: 루트 레이아웃이 AppThemeProvider 로 라이트/다크를 내려주고, 화면·부품은 useTheme() 으로 C(색)·s(스타일)를 읽음
const THEMES = { light: { C: LIGHT, s: makeStyles(LIGHT) }, dark: { C: DARK, s: makeStyles(DARK) } };
const ThemeCtx = createContext(THEMES.light);
export const AppThemeProvider = ({ dark, children }: { dark: boolean; children: ReactNode }) =>
  <ThemeCtx.Provider value={dark ? THEMES.dark : THEMES.light}>{children}</ThemeCtx.Provider>;
export const useTheme = () => useContext(ThemeCtx);

// Alert 의 버튼은 웹에서 동작하지 않아서 웹은 window.confirm/alert 로 대체
export function confirm(title: string, message: string, okText: string, onOk: () => void) {
  if (Platform.OS === 'web') { if (window.confirm(`${title}\n\n${message}`)) onOk(); return; }
  Alert.alert(title, message, [{ text: '취소', style: 'cancel' }, { text: okText, style: 'destructive', onPress: onOk }]);
}
export function notify(title: string, message = '') {
  if (Platform.OS === 'web') window.alert(message ? `${title}\n\n${message}` : title);
  else Alert.alert(title, message);
}

// 행마다 붙는 보조 동작(이름 변경·삭제 등)을 '…' 버튼 하나로 모음. 작은 글자 링크를 나란히 두지 않음
// iOS 는 시스템 액션 시트, 안드로이드·웹은 아래에서 올라오는 시트 (Alert 는 버튼 3개까지라 부족)
export type MenuItem = { text: string; onPress: () => void; destructive?: boolean };
export function ActionMenu({ title, items }: { title: string; items: MenuItem[] }) {
  const { C, s } = useTheme();
  const [open, setOpen] = useState(false);
  const { bottom } = useSafeAreaInsets();
  const show = () => {
    if (Platform.OS !== 'ios') return setOpen(true);
    const destructive = items.findIndex(i => i.destructive);
    ActionSheetIOS.showActionSheetWithOptions(
      { title, options: [...items.map(i => i.text), '취소'], cancelButtonIndex: items.length, destructiveButtonIndex: destructive < 0 ? undefined : destructive },
      i => items[i]?.onPress());
  };
  const pick = (i: MenuItem) => { setOpen(false); i.onPress(); };
  return (
    <>
      <Pressable onPress={show} hitSlop={8} accessibilityRole="button" accessibilityLabel={`${title} 더보기`}
        style={({ pressed }) => [{ width: 44, height: 44, borderRadius: 22, justifyContent: 'center', alignItems: 'center' }, pressed && { backgroundColor: C.border }]}>
        <Ionicons name="ellipsis-horizontal" size={22} color={C.sub} />
      </Pressable>
      <Modal visible={open} transparent animationType="slide" onRequestClose={() => setOpen(false)} statusBarTranslucent navigationBarTranslucent>
        <Pressable style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' }} onPress={() => setOpen(false)} accessibilityLabel="닫기" />
        <View style={{ backgroundColor: C.card, borderTopLeftRadius: 16, borderTopRightRadius: 16, paddingTop: 8, paddingBottom: 8 + bottom }}>
          <Text style={[s.small, { textAlign: 'center', paddingVertical: 8 }]}>{title}</Text>
          {[...items, { text: '취소', onPress: () => {} }].map((i, n) => (
            <Pressable key={i.text} onPress={() => pick(i)} accessibilityRole="button"
              style={({ pressed }) => [{ minHeight: 56, justifyContent: 'center', paddingHorizontal: 24, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.border }, pressed && { backgroundColor: C.bg }]}>
              <Text style={[s.body, { color: i.destructive ? C.danger : C.text, fontWeight: n === items.length ? '700' : '400' }]}>{i.text}</Text>
            </Pressable>
          ))}
        </View>
      </Modal>
    </>
  );
}

// 2~4개 중 하나를 고르는 전환 (iOS 세그먼트 컨트롤 / Material 세그먼트 버튼 역할). 각 칸 높이 44 이상
export function Segmented<T extends string>({ items, value, onChange, label }: { items: readonly { value: T; label: string }[] | readonly T[]; value: T; onChange: (v: T) => void; label?: string }) {
  const { C, s } = useTheme();
  const list = items.map(i => (typeof i === 'string' ? { value: i, label: i } : i)) as { value: T; label: string }[];
  return (
    <View accessibilityRole="tablist" accessibilityLabel={label} style={{ flexDirection: 'row', backgroundColor: C.border, borderRadius: 10, padding: 3, gap: 3 }}>
      {list.map(i => {
        const on = i.value === value;
        return (
          <Pressable key={i.value} onPress={() => onChange(i.value)} accessibilityRole="tab" accessibilityState={{ selected: on }}
            style={[{ flex: 1, minHeight: 44, borderRadius: 8, justifyContent: 'center', alignItems: 'center', paddingHorizontal: 6 }, on && { backgroundColor: C.card, ...shadow }]}>
            <Text style={[s.muted, { marginTop: 0, textAlign: 'center', color: on ? C.text : C.sub, fontWeight: on ? '700' : '500' }]}>{i.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

// a11y: 체크박스처럼 버튼이 아닌 역할·상태가 필요할 때 (기본: 누를 수 있으면 버튼)
type A11y = Pick<PressableProps, 'accessibilityRole' | 'accessibilityState' | 'accessibilityLabel' | 'accessibilityHint'>;
// 스위치 색을 한 곳에서: 켜짐 = fill, 꺼짐 = border, 손잡이 = 흰색
// activeThumbColor: react-native-web 전용 (웹은 켜짐 손잡이를 thumbColor 대신 이 값으로 그림). 네이티브 타입에 없어 웹에서만 넘김
const WEB_THUMB = Platform.OS === 'web' ? ({ activeThumbColor: '#FFFFFF' } as object) : {};
export function AppSwitch(props: SwitchProps & { accessibilityLabel: string }) {
  const { C } = useTheme();
  return <Switch trackColor={{ true: C.fill, false: C.border }} thumbColor="#FFFFFF" ios_backgroundColor={C.border} {...WEB_THUMB} {...props} />;
}

export function Card({ children, onPress, ...a11y }: { children: ReactNode; onPress?: () => void } & A11y) {
  const { s } = useTheme();
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined} {...a11y}
      style={({ pressed }) => [s.card, pressed && s.pressed]}>{children}</Pressable>
  );
}

// textColor: 채운 버튼의 글자색 (기본 C.onSign 흰색. 카카오 노란 버튼처럼 밝은 바탕이면 어두운 색을 넘김)
export function Btn({ title, onPress, color, textColor, outline, icon }:
  { title: string; onPress: () => void; color?: string; textColor?: string; outline?: boolean; icon?: IconName }) {
  const { C, s } = useTheme();
  const c = color ?? (outline ? C.primary : C.fill);
  const fg = outline ? c : textColor ?? C.onSign;
  return (
    <Pressable onPress={onPress} accessibilityRole="button"
      style={({ pressed }) => [s.btn, outline ? { borderWidth: 1.5, borderColor: c, backgroundColor: C.card } : { backgroundColor: c }, pressed && s.pressed]}>
      {icon && <Ionicons name={icon} size={20} color={fg} />}
      <Text style={[s.btnText, { color: fg }]}>{title}</Text>
    </Pressable>
  );
}

// solid: 표지판처럼 색을 꽉 채운 배지 (fg 는 그 위 글자색). 아니면 옅은 바탕 + 색 글자 (color 는 글자용 색이어야 함)
export function Badge({ text, color, icon, solid, fg }: { text: string; color: string; icon?: IconName; solid?: boolean; fg?: string }) {
  const { C, s } = useTheme();
  const ink = solid ? fg ?? C.onSign : color;
  return (
    <View style={[s.badge, { backgroundColor: solid ? color : color + '1A' }]}>
      {icon && <Ionicons name={icon} size={13} color={ink} />}
      <Text style={[s.badgeText, { color: ink }]}>{text}</Text>
    </View>
  );
}

export function Chip({ text, on, onPress }: { text: string; on: boolean; onPress: () => void }) {
  const { C, s } = useTheme();
  return (
    <Pressable onPress={onPress} style={[s.chip, on && s.chipOn]} accessibilityRole="button" accessibilityState={{ selected: on }}>
      <Text style={[s.chipText, on && { color: C.onSign }]}>{text}</Text>
    </Pressable>
  );
}

// 도로표지 판. red = 경보(규제판), yellow = 주의보(주의판), blue = 평시 상태(안내판), green = 대피 안내
// 빨강·청색·녹색은 안쪽 흰 테두리, 노랑은 바깥 빨간 테두리 (노랑만으로는 회색 바탕과 경계가 묻힘)
export type SignTone = 'red' | 'yellow' | 'blue' | 'green';
export const signInk = (C: Colors, tone: SignTone) => (tone === 'yellow' ? C.onYellow : C.onSign);
export function Sign({ tone, children, onPress, label }: { tone: SignTone; children: ReactNode; onPress?: () => void; label?: string }) {
  const { C, s } = useTheme();
  const yellow = tone === 'yellow';
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined} accessibilityLabel={label}
      style={({ pressed }) => [{ borderRadius: 12, overflow: 'hidden', backgroundColor: C.sign[tone] },
        yellow ? { borderWidth: 3, borderColor: C.sign.red } : { padding: 4 }, pressed && s.pressed]}>
      <View style={[{ gap: 4 }, yellow ? { padding: 12 } : { borderWidth: 2, borderColor: C.onSign, borderRadius: 8, padding: 14 }]}>{children}</View>
    </Pressable>
  );
}

// 표지판 안의 버튼. solid = 흰 바탕 + 판 색 글자 (주 행동), 아니면 흰 테두리 (보조 행동)
export function SignBtn({ tone, title, icon, onPress, solid }: { tone: SignTone; title: string; icon?: IconName; onPress: () => void; solid?: boolean }) {
  const { C, s } = useTheme();
  const ink = signInk(C, tone);
  const fg = solid ? (tone === 'yellow' ? C.onYellow : C.sign[tone]) : ink;
  return (
    <Pressable onPress={onPress} accessibilityRole="button"
      style={({ pressed }) => [s.btn, { flex: 1, minHeight: 48 }, solid ? { backgroundColor: C.onSign } : { borderWidth: 2, borderColor: ink }, pressed && s.pressed]}>
      {icon && <Ionicons name={icon} size={20} color={fg} />}
      <Text style={[s.btnText, { color: fg, fontWeight: '800' }]}>{title}</Text>
    </Pressable>
  );
}

// 아이콘 + 제목 (카드 안 섹션 제목)
export function Label({ icon, text, color }: { icon: IconName; text: string; color?: string }) {
  const { C, s } = useTheme();
  return (
    <View style={[s.row, { marginBottom: 6 }]}>
      <Ionicons name={icon} size={20} color={color ?? C.primary} />
      <Text style={s.bold}>{text}</Text>
    </View>
  );
}

// 아이콘 + 한 줄 정보 (상세 화면의 주소·시각 등)
export function InfoRow({ icon, text }: { icon: IconName; text: string }) {
  const { C, s } = useTheme();
  return (
    <View style={[s.row, { paddingVertical: 4 }]}>
      <Ionicons name={icon} size={18} color={C.sub} />
      <Text style={[s.body, { flex: 1 }]}>{text}</Text>
    </View>
  );
}

// 결과가 없을 때의 빈 상태 (오류가 아닌 정상 상태로 안내)
export function Empty({ icon, text }: { icon: IconName; text: string }) {
  const { C, s } = useTheme();
  return (
    <View style={{ alignItems: 'center', padding: 24, gap: 8 }}>
      <Ionicons name={icon} size={36} color={C.sub} />
      <Text style={[s.muted, { textAlign: 'center' }]}>{text}</Text>
    </View>
  );
}

// 지도가 들어갈 자리
export function MapBox({ children }: { children: ReactNode }) {
  const { C, s } = useTheme();
  return (
    <View style={s.map}>
      <Ionicons name="map-outline" size={32} color={C.primary} />
      {children}
    </View>
  );
}

// 상세 화면 제목. 네이티브 스택 헤더(뒤로가기 버튼·스와이프 포함)에 제목만 넘김
export const Header = ({ title }: { title: string }) => <Stack.Screen options={{ title }} />;

// 스크롤 가능한 본문. 입력칸을 누르면 키보드에 가리지 않도록 자동 스크롤 (bottomOffset: 키보드와의 간격)
// keyboardShouldPersistTaps: 키보드가 열린 상태에서도 버튼이 한 번에 눌리도록
// 안드로이드는 화면이 하단 내비게이션 바 뒤까지 그려지므로(edge-to-edge) 그 높이만큼 아래 여백 추가
// iPad·가로 넓은 창: 휴대폰 화면을 늘리지 않고 읽기 좋은 폭으로 가운데 정렬
export const MAX_WIDTH = 640;
// contentInsetAdjustmentBehavior: iOS 26+ 기본 헤더는 투명(Liquid Glass)이라 내용이 헤더 뒤에서 시작함.
//   'automatic' 이면 iOS 가 헤더·홈 인디케이터만큼 안쪽 여백을 직접 넣어 가려지지 않음 (iOS 전용, 다른 OS 는 무시)
//   → iOS 는 아래 여백을 시스템이 넣으므로 bottom 을 더하지 않음 (두 번 더해지지 않게)
// refreshControl: 아래로 당겨 새로고침 (홈 등 필요한 화면만 넘김)
export function Page({ children, refreshControl }: { children: ReactNode; refreshControl?: ScrollViewProps['refreshControl'] }) {
  const { bottom } = useSafeAreaInsets();
  return (
    <KeyboardAwareScrollView bottomOffset={24} keyboardShouldPersistTaps="handled" contentInsetAdjustmentBehavior="automatic" refreshControl={refreshControl}
      contentContainerStyle={{ padding: 16, gap: 12, paddingBottom: 32 + (Platform.OS === 'ios' ? 0 : bottom), width: '100%', maxWidth: MAX_WIDTH, alignSelf: 'center' }}>
      {children}
    </KeyboardAwareScrollView>
  );
}

export const STALE_MIN = 10; // 이 시간(분)이 지나면 오래된 위치
// 오래된 위치로는 영향상태를 확정하지 않음 → '판단 불가' 로 취급 (요약·정렬용)
export const knownImpact = (m: { impact: Impact; updatedMin?: number }): Impact =>
  (m.updatedMin ?? 0) > STALE_MIN && (m.impact === '영향지역 포함' || m.impact === '영향지역 밖') ? '판단 불가' : m.impact;
export const freshness = (min?: number) =>
  min === undefined ? '위치 없음' : min <= STALE_MIN ? `${min}분 전 · 최신` : `${min}분 전 · 오래된 위치`;

// 글자 단계: display 34(큰 숫자) · h1 30 · h2 20 · section/body/bold 17 · muted 15 · small 13 (하한). 시스템 글자 크기 설정에 따라 함께 커짐
function makeStyles(C: Colors) { return StyleSheet.create({
  fill: { flex: 1 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  display: { fontSize: 34, lineHeight: 40, fontWeight: '900', color: C.text, fontVariant: ['tabular-nums'] },
  h1: { fontSize: 30, lineHeight: 36, fontWeight: '800', letterSpacing: -0.5, color: C.text },
  h2: { fontSize: 20, lineHeight: 26, fontWeight: '700', color: C.text },
  section: { fontSize: 17, lineHeight: 24, fontWeight: '700', color: C.text, marginTop: 8 },
  body: { fontSize: 17, lineHeight: 24, color: C.text },
  bold: { fontSize: 17, lineHeight: 24, fontWeight: '700', color: C.text },
  muted: { fontSize: 15, lineHeight: 22, color: C.sub, marginTop: 2 },
  small: { fontSize: 13, lineHeight: 18, color: C.sub },
  num: { fontVariant: ['tabular-nums'] }, // 거리·시각 숫자 자릿수 폭 고정
  link: { color: C.primary, fontWeight: '600', fontSize: 15, lineHeight: 20, paddingVertical: 12, marginLeft: 12 }, // 터치 높이 44
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  card: { backgroundColor: C.card, borderRadius: 12, padding: 16, gap: 2, ...shadow },
  pressed: { opacity: 0.7 },
  btn: { flexDirection: 'row', gap: 8, borderRadius: 10, minHeight: 52, paddingHorizontal: 12, justifyContent: 'center', alignItems: 'center', alignSelf: 'stretch' },
  btnText: { fontWeight: '700', fontSize: 17, flexShrink: 1, textAlign: 'center' }, // 큰 글자에서 줄바꿈
  badge: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6 },
  badgeText: { fontSize: 13, fontWeight: '700' },
  chip: { borderWidth: 1, borderColor: C.border, borderRadius: 999, paddingHorizontal: 14, paddingVertical: 9, minHeight: 44, justifyContent: 'center', backgroundColor: C.card, marginBottom: 6 },
  chipOn: { backgroundColor: C.fill, borderColor: C.fill },
  chipText: { fontSize: 15, color: C.text, fontWeight: '500' },
  input: { borderWidth: 1, borderColor: C.border, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 12, marginVertical: 10, fontSize: 17, backgroundColor: C.bg, color: C.text },
  map: { height: 160, borderRadius: 12, backgroundColor: C.primarySoft, justifyContent: 'center', alignItems: 'center', gap: 6 },
  bar: { height: 10, backgroundColor: C.border, borderRadius: 5, marginTop: 10, overflow: 'hidden' },
  barFill: { height: 10, backgroundColor: C.success, borderRadius: 5 },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: C.border, marginVertical: 10 },
  notice: { backgroundColor: C.primarySoft, color: C.noticeText, fontSize: 15, lineHeight: 20, padding: 12, borderRadius: 10, textAlign: 'center' },
  noticeError: { backgroundColor: C.dangerSoft, color: C.dangerText },
}); }
