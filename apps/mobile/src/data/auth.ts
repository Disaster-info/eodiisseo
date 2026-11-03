// FS-AUTH-001·002 소셜 로그인 (iOS·Android): 네이티브 SDK 로 받은 제공자 토큰을 백엔드 /api/v1/auth/{google,kakao} 로 전달
// Google: idToken (webClientId 로 발급 → 백엔드 GOOGLE_CLIENT_ID 와 같은 대상). Kakao: accessToken (백엔드가 KAKAO_APP_ID 로 확인)
// 네이티브 SDK 는 개발 빌드에만 있음 → Expo Go 에서는 목업 로그인 (화면 확인용)
// 설정(.env.local): EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID, EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID, EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY
import { isRunningInExpoGo } from 'expo';
import { Platform } from 'react-native';
import { signIn } from './api';
import { MemberInfo, mockAuthenticate, Provider } from './mock';

const GOOGLE_WEB_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID ?? '';
const GOOGLE_IOS_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID ?? '';
const KAKAO_NATIVE_APP_KEY = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? '';

// 반환: 로그인한 회원 정보, 사용자가 로그인을 취소하면 null. 실패하면 안내 문구를 담은 Error
export function useSocialLogin() {
  return async (p: Provider): Promise<MemberInfo | null> => {
    if (isRunningInExpoGo()) return mockAuthenticate(p);
    return p === 'Google' ? google() : kakao();
  };
}

async function google(): Promise<MemberInfo | null> {
  // iOS 는 iOS 클라이언트 ID(URL scheme 은 app.config.ts 가 빌드에 넣음)가 없으면 SDK 가 앱을 종료시키므로 먼저 막음
  if (!GOOGLE_WEB_CLIENT_ID || (Platform.OS === 'ios' && !GOOGLE_IOS_CLIENT_ID)) {
    throw new Error('Google 로그인 설정이 아직 없어요. (EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID, EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID)');
  }
  // 네이티브 모듈은 개발 빌드에서만 불러옴 (Expo Go 에서 불러오면 앱이 멈춤)
  const { GoogleSignin, isSuccessResponse, isErrorWithCode, statusCodes } = await import('@react-native-google-signin/google-signin');
  GoogleSignin.configure({ webClientId: GOOGLE_WEB_CLIENT_ID, iosClientId: GOOGLE_IOS_CLIENT_ID || undefined });
  try {
    await GoogleSignin.hasPlayServices(); // Android: Google Play 서비스가 없거나 오래됐으면 안내
    const r = await GoogleSignin.signIn();
    if (!isSuccessResponse(r)) return null; // 사용자가 취소
    if (!r.data.idToken) throw new Error('Google 인증 정보를 받지 못했어요. 다시 시도해 주세요.');
    return await signIn('Google', { idToken: r.data.idToken });
  } catch (e) {
    if (isErrorWithCode(e)) {
      if (e.code === statusCodes.IN_PROGRESS) return null; // 이미 진행 중인 로그인 창이 있음
      if (e.code === statusCodes.PLAY_SERVICES_NOT_AVAILABLE) throw new Error('Google Play 서비스를 사용할 수 없어요. 업데이트 후 다시 시도해 주세요.');
      throw new Error('Google 로그인 중 오류가 났어요. 다시 시도해 주세요.');
    }
    throw e;
  }
}

async function kakao(): Promise<MemberInfo | null> {
  if (!KAKAO_NATIVE_APP_KEY) throw new Error('카카오 로그인 설정이 아직 없어요. (EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY)');
  const { login } = await import('@react-native-seoul/kakao-login');
  let accessToken: string;
  try {
    accessToken = (await login()).accessToken; // 카카오톡 앱이 있으면 앱으로, 없으면 카카오계정 로그인 화면
  } catch (e) {
    // 라이브러리가 취소를 별도 코드로 주지 않아 문구로 구분
    if (/cancel/i.test(e instanceof Error ? e.message : String(e))) return null;
    throw new Error('카카오 로그인 중 오류가 났어요. 다시 시도해 주세요.');
  }
  return signIn('Kakao', { accessToken });
}
