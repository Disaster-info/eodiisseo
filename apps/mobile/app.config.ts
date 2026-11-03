// 앱 설정 = app.json + 소셜 로그인 네이티브 설정. 키(비밀값 아님)는 .env.local·EAS 환경변수에서 읽어 빌드에 넣음
// 키가 없으면 해당 플러그인을 빼서 개발 서버·다른 기능은 그대로 동작 (그 로그인만 '설정 없음' 안내)
import type { ConfigContext, ExpoConfig } from 'expo/config';

export default ({ config }: ConfigContext): ExpoConfig => {
  const googleIosClientId = process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID;
  const kakaoNativeAppKey = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY;
  return {
    ...config,
    name: config.name ?? 'Disaster',
    slug: config.slug ?? 'Disaster',
    plugins: [
      ...(config.plugins ?? []),
      // Google iOS: 클라이언트 ID 를 뒤집은 URL scheme (….apps.googleusercontent.com → com.googleusercontent.apps.…)
      ...(googleIosClientId
        ? [['@react-native-google-signin/google-signin', { iosUrlScheme: googleIosClientId.split('.').reverse().join('.') }] as [string, object]]
        : []),
      // 카카오: 네이티브 앱 키 → iOS URL scheme·Android 리다이렉트(kakao{키}://oauth) 등록
      ...(kakaoNativeAppKey ? [['@react-native-seoul/kakao-login', { kakaoAppKey: kakaoNativeAppKey }] as [string, object]] : []),
    ],
  };
};
