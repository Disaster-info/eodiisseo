// 앱 시작점: 전역 상태 + 테마 + 화면 스택. 로그인 여부로 접근 가능한 화면을 나눔 (FS-AUTH-004)
import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';
import { KeyboardProvider } from 'react-native-keyboard-controller';
import { AppThemeProvider, useTheme } from '../components/ui';
import { AppProvider, useApp } from '../store/AppContext';

// 테마 모드(시스템/라이트/다크) → 실제 라이트·다크 결정 후 화면 전체에 색을 내려줌
function RootStack() {
  const { theme } = useApp();
  const system = useColorScheme();
  const dark = theme === 'system' ? system === 'dark' : theme === 'dark';
  return <AppThemeProvider dark={dark}><Navigator dark={dark} /></AppThemeProvider>;
}

function Navigator({ dark }: { dark: boolean }) {
  const { member, needConsent, needLocation } = useApp();
  const { C } = useTheme();
  const base = dark ? DarkTheme : DefaultTheme;
  const navTheme = { ...base, colors: { ...base.colors, primary: C.primary, background: C.bg, card: C.card, text: C.text, border: C.border } };

  return (
    <ThemeProvider value={navTheme}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerBackButtonDisplayMode: 'minimal', headerTintColor: C.text, headerTitleStyle: { fontWeight: '700' } }}>
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
            <Stack.Screen name="shelters/[id]" />
            <Stack.Screen name="route/[id]" />
            <Stack.Screen name="groups/[id]" />
            <Stack.Screen name="areas" />
            <Stack.Screen name="noti" />
            <Stack.Screen name="chat" />
            <Stack.Screen name="ops" />
          </Stack.Protected>
        </Stack.Protected>
      </Stack>
    </ThemeProvider>
  );
}

export default function RootLayout() {
  return (
    // KeyboardProvider: 키보드 애니메이션을 프레임 단위로 받아 입력창을 키보드와 동시에 움직임 (Expo Go 포함)
    <KeyboardProvider>
      <AppProvider>
        <RootStack />
      </AppProvider>
    </KeyboardProvider>
  );
}
