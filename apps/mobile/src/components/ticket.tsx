// C안(탑승권) 공통 부품 — 안심그룹·체크리스트·대피소가 함께 씀
// 쿠폰(머리 띠가 있는 카드) · 절취선 · 도장 · 칸(이름표 + 값) · 남색 띠 위 선택 칩 · 고정폭 숫자
import { ReactNode } from 'react';
import { Platform, Pressable, PressableProps, ScrollView, Text, View } from 'react-native';
import { Icon, IconName, useTheme } from './ui';

// 숫자·코드는 승차권처럼 고정폭 글꼴 (글꼴 파일을 넣지 않고 기기 기본 고정폭 사용)
export const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'ui-monospace, Menlo, monospace' });

type A11y = Pick<PressableProps, 'accessibilityLabel' | 'accessibilityHint'>;
export type CouponTone = 'navy' | 'red' | 'yellow' | 'green';
const BAND = { red: '#D7261E', yellow: '#F5C400', green: '#0E7A4F' };

// head: 머리 띠 제목, tone: 띠 색 (navy 기본, red = 경보, yellow = 주의보, green = 대피), right: 띠 오른쪽 작은 글자·배지
export function Coupon({ head, headIcon, right, tone = 'navy', onPress, children, dashed, ...a11y }:
  { head?: string; headIcon?: IconName; right?: ReactNode; tone?: CouponTone; onPress?: () => void; children: ReactNode; dashed?: boolean } & A11y) {
  const { C, s } = useTheme();
  // 띠 색은 테마와 무관하게 원색 판 (다크 모드에서도 같은 띠). 노랑 띠만 어두운 글자
  const band = tone === 'navy' ? C.navy : BAND[tone];
  const fg = tone === 'yellow' ? '#16181B' : '#FFFFFF';
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined} {...a11y}
      style={({ pressed }) => [s.card, { padding: 0, gap: 0 },
        dashed && { backgroundColor: 'transparent', borderWidth: 1.5, borderStyle: 'dashed', borderColor: C.perf, boxShadow: 'none', elevation: 0, shadowOpacity: 0 },
        pressed && s.pressed]}>
      {!!head && (
        <View style={[s.row, { backgroundColor: band, borderTopLeftRadius: 12, borderTopRightRadius: 12, paddingHorizontal: 16, paddingVertical: 10 }]}>
          {headIcon && <Icon name={headIcon} size={20} color={fg} />}
          <Text style={[s.bold, { color: fg, flex: 1, fontWeight: '900' }]} accessibilityRole="header">{head}</Text>
          {typeof right === 'string' ? <Text style={[s.small, { color: fg, fontWeight: '700' }]}>{right}</Text> : right}
        </View>
      )}
      <View style={{ padding: 16, gap: 2 }}>{children}</View>
    </Pressable>
  );
}

// 절취선: 점선 + 양쪽 반원 홈 (쿠폰 안 padding 16 을 넘어 카드 끝까지)
export function Perf() {
  const { C } = useTheme();
  const notch = { position: 'absolute', top: -9, width: 18, height: 18, borderRadius: 9, backgroundColor: C.bg } as const;
  return (
    <View style={{ marginHorizontal: -16, marginVertical: 12, justifyContent: 'center' }} accessible={false}>
      {/* iOS 는 한쪽 테두리만 점선이 안 되어 높이 0 상자의 테두리 전체를 점선으로 */}
      <View style={{ marginHorizontal: 14, height: 0, borderWidth: 1, borderStyle: 'dashed', borderColor: C.perf, borderRadius: 1 }} />
      <View style={[notch, { left: -9 }]} />
      <View style={[notch, { right: -9 }]} />
    </View>
  );
}

// 도장: 테두리 글자를 살짝 기울임. dashed = 확실하지 않은 상태(판단 불가·미제공)
export function Stamp({ text, color, icon, dashed }: { text: string; color: string; icon?: IconName; dashed?: boolean }) {
  const { s } = useTheme();
  return (
    <View style={[s.row, { gap: 3, borderWidth: dashed ? 1.5 : 2, borderStyle: dashed ? 'dashed' : 'solid', borderColor: color, borderRadius: 4,
      paddingHorizontal: 7, paddingVertical: 2, transform: [{ rotate: dashed ? '0deg' : '-4deg' }] }]}>
      {icon && <Icon name={icon} size={14} color={color} />}
      <Text style={[s.small, { color, fontWeight: '900' }]}>{text}</Text>
    </View>
  );
}

// 칸: 작은 이름표 + 값 (승차권의 '출발·도착·좌석' 칸). mono = 고정폭 숫자
export function Field({ label, value, color, mono }: { label: string; value: string; color?: string; mono?: boolean }) {
  const { C, s } = useTheme();
  return (
    <View style={{ flex: 1, minWidth: 0 }} accessible accessibilityLabel={`${label} ${value}`}>
      <Text style={s.small}>{label}</Text>
      <Text style={[s.bold, mono && { fontFamily: MONO, fontSize: 20, lineHeight: 26, color: C.carbon }, color && { color }]} numberOfLines={1}>{value}</Text>
    </View>
  );
}

// 상단 띠 위의 선택 칩 (탭 역할). 선택 = 흰 바탕 + 띠 색 글자
export function BandTabs<T extends string>({ items, value, onChange, label }:
  { items: readonly { value: T; label: string }[]; value: T; onChange: (v: T) => void; label: string }) {
  const { C, s } = useTheme();
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} accessibilityRole="tablist" accessibilityLabel={label} contentContainerStyle={{ gap: 8 }}>
      {items.map(i => {
        const on = i.value === value;
        return (
          <Pressable key={i.value} onPress={() => onChange(i.value)} accessibilityRole="tab" accessibilityState={{ selected: on }}
            style={({ pressed }) => [{ minHeight: 44, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1.5, justifyContent: 'center' },
              on ? { backgroundColor: '#FFFFFF', borderColor: '#FFFFFF' } : { borderColor: C.headLine }, pressed && s.pressed]}>
            <Text style={[s.muted, { marginTop: 0, fontWeight: '700', color: on ? C.head : C.headSub }]}>{i.label}</Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}
