// FS-AUTH-001 Google 로그인, FS-AUTH-002 Kakao 로그인, FS-AUTH-004 만료 후 재인증 안내
import { useRef, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { SocialButtons } from '../../components/social';
import { Btn, Icon, useLayout, useTheme } from '../../components/ui';
import { useSocialLogin } from '../../data/auth';
import { Provider, mockAdminAuthenticate } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function LoginScreen() {
  const { C, s } = useTheme();
  const { column } = useLayout();
  const { login, loginNotice } = useApp();
  const [pending, setPending] = useState<string | null>(null); // 진행 중인 로그인 이름 (Google·Kakao·관리자)
  const [error, setError] = useState('');
  const attempt = useRef(0); // 취소된 인증 결과를 무시하기 위한 번호
  const authenticate = useSocialLogin();

  const start = async (p: Provider) => {
    const my = ++attempt.current;
    setError(''); setPending(p);
    try {
      const info = await authenticate(p);
      if (my !== attempt.current) return;
      if (info) login(p, info);
      else setPending(null); // 로그인 창을 닫음 → 화면 유지
    } catch (e) {
      if (my === attempt.current) {
        setPending(null);
        setError(e instanceof Error && e.message ? e.message : `${p} 로그인에 실패했습니다. 네트워크 상태를 확인하고 다시 시도해 주세요.`);
      }
    }
  };
  // 관리자 로그인 (관리자 설정 탭이 있는 관리자 UI). 제공자 표시는 Google 로 둠 (목업)
  const adminLogin = async () => {
    const my = ++attempt.current;
    setError(''); setPending('관리자');
    const info = await mockAdminAuthenticate();
    if (my === attempt.current) login('Google', info);
  };
  // 인증 취소: 로그인하지 않고 현재 화면 유지
  const cancel = () => { attempt.current++; setPending(null); };

  return (
    <View style={[s.center, { backgroundColor: C.card }]}>
      {/* 로그인 양식은 웹에서도 400 칸 가운데 (안내 문구가 끝까지 늘어나지 않게) */}
      <View style={[column, { maxWidth: 400, alignItems: 'center', gap: 12 }]}>
      <View style={{ width: 88, height: 88, borderRadius: 28, backgroundColor: C.fill, justifyContent: 'center', alignItems: 'center' }}>
        <Icon name="location" size={48} color="#fff" />
      </View>
      <Text style={[s.h1, { marginTop: 8 }]}>어디있어</Text>
      <Text style={s.muted}>실시간 재난 정보와 가족 안심 확인</Text>
      <View style={{ height: 32 }} />
      {!!loginNotice && <Text style={[s.notice, { alignSelf: 'stretch' }]}>{loginNotice}</Text>}
      {!!error && <Text style={[s.notice, s.noticeError, { alignSelf: 'stretch' }]}>{error}</Text>}
      {pending ? (
        <>
          <ActivityIndicator size="large" color={C.primary} />
          <Text style={s.body}>{pending} 인증 진행 중…</Text>
          <Btn title="취소" outline onPress={cancel} />
        </>
      ) : (
        <>
          <SocialButtons onPress={start} />
          {/* 목업 관리자 로그인은 개발 빌드에서만 (배포판에는 버튼이 없음). 관리자 인증 API 가 생기면 교체 */}
          {__DEV__ && (
            <Pressable onPress={adminLogin} accessibilityRole="button" style={{ minHeight: 44, justifyContent: 'center', marginTop: 16 }}>
              <Text style={[s.small, { textDecorationLine: 'underline' }]}>관리자 로그인 (개발용)</Text>
            </Pressable>
          )}
        </>
      )}
      </View>
    </View>
  );
}
