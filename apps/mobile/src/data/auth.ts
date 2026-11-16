// FS-AUTH-001·002 소셜 로그인 (iOS·Android): 아직 목업. 실제 로그인은 웹(auth.web.ts) 먼저 구현
// ponytail: 앱은 네이티브 SDK(@react-native-google-signin/google-signin, @react-native-seoul/kakao-login) + 개발 빌드가 필요
import { MemberInfo, mockAuthenticate, Provider } from './mock';

// 반환: 로그인한 회원 정보, 사용자가 로그인 창을 닫으면 null. 실패하면 안내 문구를 담은 Error
export function useSocialLogin() {
  return (p: Provider): Promise<MemberInfo | null> => mockAuthenticate(p);
}
