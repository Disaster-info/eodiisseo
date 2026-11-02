// 소셜 로그인 버튼 (iOS·Android·웹 공통) — 각 사 브랜드 가이드 규격. 로고는 원본 그대로(색·모양 변경 금지), 앱 색으로 칠하지 않음
// Google (developers.google.com/identity/branding-guidelines): light/dark 테마 색, 표준 4색 G 는 흰 바탕 위,
//   글꼴 Google Sans Medium, 로고 앞 여백 iOS 16 / Android·웹 12, 비율을 지키면 버튼 크기 조절 허용
// Kakao (developers.kakao.com 카카오 로그인 디자인 가이드): #FEE500 바탕, 검정 심볼, 레이블 검정 85%, 모서리 12,
//   OS 기본 서체, 심볼 좌측 정렬 허용, 레이블 높이는 버튼 높이의 1/3 이하
// 두 버튼은 같은 크기·같은 무게로 나란히 둠 (어느 한쪽을 더 눈에 띄게 하지 않음). 높이는 앱 규칙(52)
import { ReactNode } from 'react';
import { Platform, Pressable, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { Provider } from '../data/mock';
import { useTheme } from './ui';

const GOOGLE = {
  light: { bg: '#FFFFFF', border: '#747775', text: '#1F1F1F' },
  dark: { bg: '#131314', border: '#8E918F', text: '#E3E3E3' },
};
const KAKAO = { bg: '#FEE500', text: 'rgba(0,0,0,0.85)' };
// ponytail: Google 레이블은 Google Sans Medium 이어야 하나 글꼴 파일을 넣지 않음. 웹은 설치돼 있으면 쓰고, 없으면 시스템 글꼴.
// 앱에도 정확히 맞추려면 expo-font 로 Google Sans 를 번들
const GOOGLE_FONT = Platform.OS === 'web' ? "'Google Sans', Roboto, system-ui, sans-serif" : undefined;
const LOGO_LEFT = Platform.OS === 'ios' ? 16 : 12; // Google 가이드의 로고 앞 여백. 카카오도 같은 선에 맞춤

const GoogleG = () => (
  <Svg width={20} height={20} viewBox="0 0 18 18">
    <Path fill="#EA4335" d="M9 3.48c1.69 0 2.83.73 3.48 1.34l2.54-2.48C13.46.89 11.43 0 9 0 5.48 0 2.44 2.02.96 4.96l2.91 2.26C4.6 5.05 6.62 3.48 9 3.48z" />
    <Path fill="#4285F4" d="M17.64 9.2c0-.74-.06-1.28-.19-1.84H9v3.34h4.96c-.1.83-.64 2.08-1.84 2.92l2.84 2.2c1.7-1.57 2.68-3.88 2.68-6.62z" />
    <Path fill="#FBBC05" d="M3.88 10.78A5.54 5.54 0 0 1 3.58 9c0-.62.11-1.22.29-1.78L.96 4.96A9.008 9.008 0 0 0 0 9c0 1.45.35 2.82.96 4.04l2.92-2.26z" />
    <Path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.84-2.2c-.76.53-1.78.9-3.12.9-2.38 0-4.4-1.57-5.12-3.74L.97 13.04C2.45 15.98 5.48 18 9 18z" />
  </Svg>
);

const KakaoSymbol = () => (
  <Svg width={20} height={20} viewBox="0 0 18 18">
    <Path fill="#000000" d="M9 1.2C4.58 1.2 1 4 1 7.44c0 2.22 1.48 4.17 3.7 5.27l-.76 2.79c-.07.25.22.45.43.3l3.32-2.2c.43.05.87.07 1.31.07 4.42 0 8-2.79 8-6.23S13.42 1.2 9 1.2z" />
  </Svg>
);

// 로고는 왼쪽 고정, 글자는 버튼 가운데 (두 가이드 공통 배치)
function BrandButton({ label, logo, bg, fg, border, radius, font, onPress }:
  { label: string; logo: ReactNode; bg: string; fg: string; border?: string; radius: number; font?: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={label}
      style={({ pressed }) => [{
        flexDirection: 'row', alignItems: 'center', justifyContent: 'center', minHeight: 52,
        paddingHorizontal: 48, borderRadius: radius, backgroundColor: bg, borderWidth: border ? 1 : 0, borderColor: border,
      }, pressed && { opacity: 0.7 }]}>
      <View style={{ position: 'absolute', left: LOGO_LEFT }}>{logo}</View>
      <Text style={{ color: fg, fontSize: 17, fontWeight: '500', fontFamily: font, textAlign: 'center' }}>{label}</Text>
    </Pressable>
  );
}

export function SocialButtons({ onPress }: { onPress: (p: Provider) => void }) {
  const { dark } = useTheme();
  const g = dark ? GOOGLE.dark : GOOGLE.light;
  // 다크 테마에서도 G 는 흰 바탕 위에 둠 (가이드: 표준 색 G 는 흰 바탕에)
  const gLogo = dark
    ? <View style={{ width: 28, height: 28, borderRadius: 14, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginLeft: -4 }}><GoogleG /></View>
    : <GoogleG />;
  return (
    // 넓은 화면(태블릿·웹)에서 끝까지 늘어나지 않게 최대 폭 400
    <View style={{ alignSelf: 'stretch', alignItems: 'center' }}>
      <View style={{ width: '100%', maxWidth: 400, gap: 12 }}>
        <BrandButton label="Google 계정으로 로그인" logo={gLogo} bg={g.bg} fg={g.text} border={g.border} radius={4}
          font={GOOGLE_FONT} onPress={() => onPress('Google')} />
        <BrandButton label="카카오 로그인" logo={<KakaoSymbol />} bg={KAKAO.bg} fg={KAKAO.text} radius={12} onPress={() => onPress('Kakao')} />
      </View>
    </View>
  );
}
