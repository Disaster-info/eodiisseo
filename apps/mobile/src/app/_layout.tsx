// 앱 시작점: 전역 상태 + 테마 + 화면 스택. 로그인 여부로 접근 가능한 화면을 나눔 (FS-AUTH-004)
import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';
import { KeyboardProvider } from 'react-native-keyboard-controller';
import { C, applyTheme } from '../components/ui';
import { AppProvider, useApp } from '../store/AppContext';

function RootStack() {
  const { theme, member } = useApp();
  const system = useColorScheme();
  const dark = theme === 'system' ? system === 'dark' : theme === 'dark';
  applyTheme(dark); // 자식 화면이 그려지기 전에 색상 교체
  const base = dark ? DarkTheme : DefaultTheme;
  const navTheme = { ...base, colors: { ...base.colors, primary: C.primary, background: C.bg, card: C.card, text: C.text, border: C.border } };

  return (
    <ThemeProvider value={navTheme}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerBackButtonDisplayMode: 'minimal', headerTintColor: C.text, headerTitleStyle: { fontWeight: '700' } }}>
        {/* 비로그인: 로그인 화면만. 로그인 여부가 바뀌면 라우터가 알맞은 화면으로 자동 이동 */}
        <Stack.Protected guard={!member}>
          <Stack.Screen name="login" options={{ headerShown: false }} />
        </Stack.Protected>
        <Stack.Protected guard={!!member}>
          <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          <Stack.Screen name="consent" options={{ headerShown: false, gestureEnabled: false }} />
          <Stack.Screen name="location" />
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
