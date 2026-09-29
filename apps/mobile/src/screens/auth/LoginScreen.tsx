// FS-AUTH-001 Google 로그인, FS-AUTH-002 Kakao 로그인, FS-AUTH-004 만료 후 재인증 안내
import { useRef, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { Btn, C, Icon, s } from '../../components/ui';
import { mockAuthenticate, Provider } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function LoginScreen() {
  const { login, loginNotice } = useApp();
  const [pending, setPending] = useState<Provider | null>(null);
  const [error, setError] = useState('');
  const attempt = useRef(0); // 취소된 인증 결과를 무시하기 위한 번호

  const start = async (p: Provider) => {
    const my = ++attempt.current;
    setError(''); setPending(p);
    try {
      const info = await mockAuthenticate(p);
      if (my === attempt.current) login(p, info);
    } catch {
      if (my === attempt.current) { setPending(null); setError(`${p} 로그인에 실패했습니다. 네트워크 상태를 확인하고 다시 시도해 주세요.`); }
    }
  };
  // 인증 취소: 로그인하지 않고 현재 화면 유지
  const cancel = () => { attempt.current++; setPending(null); };

  return (
    <View style={[s.center, { gap: 12, backgroundColor: C.card }]}>
      <View style={{ width: 88, height: 88, borderRadius: 28, backgroundColor: C.primary, justifyContent: 'center', alignItems: 'center' }}>
        <Icon name="location" size={48} color="#fff" />
      </View>
      <Text style={[s.h1, { marginTop: 8 }]}>어디있어</Text>
      <Text style={[s.muted, { fontSize: 15 }]}>실시간 재난 정보와 가족 안심 확인</Text>
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
          <Btn title="Google로 시작하기" icon="logo-google" color={C.primary} onPress={() => start('Google')} />
          <Btn title="카카오로 시작하기" icon="chatbubble" color="#F7C600" onPress={() => start('Kakao')} />
        </>
      )}
    </View>
  );
}
