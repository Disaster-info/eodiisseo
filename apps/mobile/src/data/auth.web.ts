// FS-AUTH-001·002 소셜 로그인 (웹): 로그인 창(팝업)에서 받은 인가 코드만 백엔드 /api/v1/auth/{google,kakao} 로 보냄
// 백엔드가 코드 → 제공자 토큰 교환(Client Secret 은 서버에만) → 검증 → 세션 발급. 앱에는 제공자 토큰이 남지 않음
// 설정(.env.local): EXPO_PUBLIC_API_URL, EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID, EXPO_PUBLIC_KAKAO_REST_API_KEY
import { AuthSessionResult, makeRedirectUri, ResponseType, useAuthRequest } from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import { signIn } from './api';
import { MemberInfo, Provider } from './mock';

// 로그인 창이 우리 주소로 돌아오면 결과를 원래 창에 넘기고 창을 닫음 (앱이 열릴 때 한 번)
WebBrowser.maybeCompleteAuthSession();

const GOOGLE_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID ?? '';
const KAKAO_REST_KEY = process.env.EXPO_PUBLIC_KAKAO_REST_API_KEY ?? '';
const GOOGLE = { authorizationEndpoint: 'https://accounts.google.com/o/oauth2/v2/auth' };
const KAKAO = { authorizationEndpoint: 'https://kauth.kakao.com/oauth/authorize' };

// 반환: 로그인한 회원 정보, 사용자가 로그인 창을 닫으면 null. 실패하면 안내 문구를 담은 Error
export function useSocialLogin() {
  const redirectUri = makeRedirectUri(); // 웹: 지금 주소(origin). Google·카카오 콘솔과 백엔드 교환 요청에 같은 값
  // Google 은 PKCE 사용 → 코드와 함께 codeVerifier 를 백엔드에 넘김
  const [google, , promptGoogle] = useAuthRequest({
    clientId: GOOGLE_CLIENT_ID, redirectUri, responseType: ResponseType.Code, scopes: ['openid', 'profile'],
  }, GOOGLE);
  const [kakao, , promptKakao] = useAuthRequest({
    clientId: KAKAO_REST_KEY, redirectUri, responseType: ResponseType.Code, scopes: [], usePKCE: false,
  }, KAKAO);

  return async (p: Provider): Promise<MemberInfo | null> => {
    if (p === 'Google') {
      if (!GOOGLE_CLIENT_ID || !google) throw new Error('Google 로그인 설정이 아직 없어요. (EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID)');
      const r = await promptGoogle().catch(popupBlocked);
      if (r.type !== 'success') return cancelled(r);
      return signIn('Google', { code: r.params.code, redirectUri, codeVerifier: google.codeVerifier });
    }
    if (!KAKAO_REST_KEY || !kakao) throw new Error('카카오 로그인 설정이 아직 없어요. (EXPO_PUBLIC_KAKAO_REST_API_KEY)');
    const r = await promptKakao().catch(popupBlocked);
    if (r.type !== 'success') return cancelled(r);
    return signIn('Kakao', { code: r.params.code, redirectUri });
  };
}

// 브라우저가 로그인 창(팝업)을 막은 경우 (라이브러리 문구는 영어)
function popupBlocked(): never {
  throw new Error('브라우저가 로그인 창을 막았어요. 이 사이트의 팝업을 허용하고 다시 시도해 주세요.');
}

// 창을 닫음·취소 = null (로그인 화면 유지). 제공자가 오류를 돌려주면 실패
function cancelled(r: AuthSessionResult): null {
  if (r.type === 'error') throw new Error(r.error?.description ?? r.params.error_description ?? '로그인 중 오류가 났어요. 다시 시도해 주세요.');
  return null;
}
