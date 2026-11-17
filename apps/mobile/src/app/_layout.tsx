// 앱 시작점: 전역 상태 + 테마 + 화면 스택. 로그인 여부로 접근 가능한 화면을 나눔 (FS-AUTH-004)
import { DarkTheme, DefaultTheme, Stack, ThemeProvider, type ErrorBoundaryProps } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { Pressable, Text, View, useColorScheme } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { KeyboardProvider } from 'react-native-keyboard-controller';
import { AppThemeProvider, useTheme } from '../components/ui';
import { AppProvider, useApp } from '../store/AppContext';

// 앱 시작: 저장된 로그인 확인이 끝날 때까지 스플래시를 그대로 둠 (끝나면 Navigator 가 숨김). 웹은 아무 일도 하지 않음
SplashScreen.preventAutoHideAsync().catch(() => {});

// 테마 모드(시스템/라이트/다크) → 실제 라이트·다크 결정 후 화면 전체에 색을 내려줌
function RootStack() {
  const { theme } = useApp();
  const system = useColorScheme();
  const dark = theme === 'system' ? system === 'dark' : theme === 'dark';
  return <AppThemeProvider dark={dark}><Navigator dark={dark} /></AppThemeProvider>;
}

function Navigator({ dark }: { dark: boolean }) {
  const { ready, member, needConsent, needLocation } = useApp();
  const { C } = useTheme();
  useEffect(() => { if (ready) SplashScreen.hideAsync().catch(() => {}); }, [ready]);
  if (!ready) return null; // 저장된 로그인 확인 중 — 이 동안은 스플래시가 덮고 있음. 끝나면 로그인 화면 또는 바로 홈
  const base = dark ? DarkTheme : DefaultTheme;
  const navTheme = { ...base, colors: { ...base.colors, primary: C.primary, background: C.bg, card: C.card, text: C.text, border: C.border } };

  return (
    <ThemeProvider value={navTheme}>
      {/* 로그인·위치 동의 화면(밝은 바탕, 헤더 없음) 밖은 모두 페트롤 블루 헤더 또는 어두운 홈이라 밝은 상태 표시줄 */}
      <StatusBar style={dark || (member && !needConsent) ? 'light' : 'dark'} />
      {/* 상세 화면들도 페트롤 블루 헤더 + 흰 글자 (탭 화면과 같은 머리) */}
      <Stack screenOptions={{ headerBackButtonDisplayMode: 'minimal', headerTintColor: '#FFFFFF', headerTitleStyle: { fontWeight: '700', color: '#FFFFFF' },
        headerStyle: { backgroundColor: C.head }, headerShadowVisible: false }}>
        {/* 상태마다 열 수 있는 화면만 남김 → 상태가 바뀌면 라우터가 첫 번째로 열 수 있는 화면으로 한 번에 이동
            비로그인 → 로그인 / 로그인 직후 동의 전 → 위치 동의 / 동의 거부·기준 위치 없음 → 위치 직접 지정 / 그 외 → 탭
            (탭을 거쳐 다시 넘어가지 않으므로 화면이 두 번 바뀌지 않음) */}
        <Stack.Protected guard={!member}>
          <Stack.Screen name="login" options={{ headerShown: false }} />
        </Stack.Protected>
        <Stack.Protected guard={!!member && needConsent}>
          <Stack.Screen name="consent" options={{ headerShown: false, gestureEnabled: false }} />
        </Stack.Protected>
        <Stack.Protected guard={!!member && !needConsent}>
          <Stack.Protected guard={!needLocation}>
            <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          </Stack.Protected>
          {/* 기준 위치가 없을 때의 첫 화면이자, 내정보에서 다시 여는 설정 화면 */}
          <Stack.Screen name="location" />
          <Stack.Protected guard={!needLocation}>
            <Stack.Screen name="disasters/index" />
            <Stack.Screen name="disasters/[id]" />
            <Stack.Screen name="route/[id]" />
            {/* 운영자: 개인용 안심그룹은 숨김, 운영 공지는 운영자만 */}
            <Stack.Protected guard={!member?.operator}>
              <Stack.Screen name="groups/[id]" />
            </Stack.Protected>
            <Stack.Protected guard={!!member?.operator}>
              <Stack.Screen name="notice" />
            </Stack.Protected>
            <Stack.Screen name="areas" />
            <Stack.Screen name="noti" />
            <Stack.Screen name="chat" />
          </Stack.Protected>
        </Stack.Protected>
      </Stack>
    </ThemeProvider>
  );
}

export default function RootLayout() {
  return (
    // KeyboardProvider: 키보드 애니메이션을 프레임 단위로 받아 입력창을 키보드와 동시에 움직임 (Expo Go 포함)
    // GestureHandlerRootView: 탭 좌우 스와이프(react-native-gesture-handler)가 동작하려면 앱 맨 바깥에 필요
    <GestureHandlerRootView style={{ flex: 1 }}>
      <KeyboardProvider>
        <AppProvider>
          <RootStack />
        </AppProvider>
      </KeyboardProvider>
    </GestureHandlerRootView>
  );
}

// 화면 코드에서 예상 못 한 오류가 나면 흰 화면·영어 오류 대신 한국어 안내 + 다시 시도 (Expo Router 가 이 함수를 오류 화면으로 씀)
// 테마·전역 상태 바깥에서 그려질 수 있어 색은 직접 지정. 오류 내용은 개발 빌드에서만 보여 줌
export function ErrorBoundary({ error, retry }: ErrorBoundaryProps) {
  useEffect(() => { SplashScreen.hideAsync().catch(() => {}); }, []); // 준비 중 오류가 나도 스플래시에 가려지지 않게
  return (
    <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24, gap: 12, backgroundColor: '#E7EAF0' }}>
      <Text style={{ fontSize: 22, fontWeight: '800', color: '#16181B' }} accessibilityRole="header">일시적인 오류가 났어요</Text>
      <Text style={{ fontSize: 16, lineHeight: 24, color: '#4E5B6A', textAlign: 'center' }}>
        다시 시도해도 계속되면 앱을 껐다 켜 주세요.{'\n'}위급한 상황이면 바로 119에 신고하세요.
      </Text>
      {__DEV__ && <Text style={{ fontSize: 13, color: '#B3201A', textAlign: 'center' }} selectable>{error.message}</Text>}
      <Pressable onPress={retry} accessibilityRole="button"
        style={({ pressed }) => [{ minHeight: 52, minWidth: 200, borderRadius: 10, backgroundColor: '#0B4A6B', justifyContent: 'center', alignItems: 'center', paddingHorizontal: 20 }, pressed && { opacity: 0.7 }]}>
        <Text style={{ color: '#FFFFFF', fontSize: 17, fontWeight: '700' }}>다시 시도</Text>
      </Pressable>
    </View>
  );
}
