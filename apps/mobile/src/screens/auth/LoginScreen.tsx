// FS-AUTH-001 Google 로그인, FS-AUTH-002 Kakao 로그인, FS-AUTH-004 만료 후 재인증 안내
import { useRef, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { SocialButtons } from '../../components/social';
import { Btn, Icon, confirm, useLayout, useTheme } from '../../components/ui';
import { WithdrawalPending, endSession, sessionAccount } from '../../data/api';
import { useSocialLogin } from '../../data/auth';
import { MemberInfo, Provider } from '../../data/types';
import { useApp } from '../../store/AppContext';

export default function LoginScreen() {
  const { C, s } = useTheme();
  const { column } = useLayout();
  const { login, loginNotice } = useApp();
  const [pending, setPending] = useState<string | null>(null); // 진행 중인 로그인 이름 (Google·Kakao). 운영자도 같은 소셜 로그인 (서버의 OPS 권한으로 구분)
  const [error, setError] = useState('');
  const attempt = useRef(0); // 취소된 인증 결과를 무시하기 위한 번호
  const authenticate = useSocialLogin();

  const start = async (p: Provider) => {
    const my = ++attempt.current;
    setError(''); setPending(p);
    try {
      const cancelled = () => my !== attempt.current;
      const info = await authenticate(p, cancelled); // 취소하면 토큰을 저장하지 않음
      if (!info) { if (!cancelled()) setPending(null); return; } // 로그인 창을 닫음 → 화면 유지
      const mine = sessionAccount(); // 이 시도가 저장한 세션
      // 인증이 끝난 뒤(또는 저장된 설정을 읽는 사이) 취소했으면 이 시도가 저장한 토큰을 지움 (다시 열었을 때 취소한 계정으로 로그인되지 않게)
      // 그 사이 다른 로그인이 세션을 바꿨으면 그 세션은 건드리지 않음
      if (cancelled() || !(await login(p, info, cancelled))) { if (sessionAccount() === mine) await endSession(); }
    } catch (e) {
      if (my !== attempt.current) return;
      setPending(null);
      setError(e instanceof Error && e.message ? e.message : `${p} 로그인에 실패했습니다. 네트워크 상태를 확인하고 다시 시도해 주세요.`);
      // FS-AUTH-007: 탈퇴 유예 중인 계정 → 복구할지 물음 (앱 로그인만 가능, 웹은 위 안내 문구만)
      if (e instanceof WithdrawalPending && e.recover) {
        const recover = e.recover;
        confirm('계정 복구', `${e.until ? `${e.until}까지 ` : ''}탈퇴를 취소하고 계정을 복구할 수 있어요.\n탈퇴할 때 나간 안심그룹은 복구되지 않아요. 복구할까요?`, '복구', () => recoverNow(p, recover));
      }
    }
  };
  const recoverNow = async (p: Provider, recover: (isCancelled: () => boolean) => Promise<MemberInfo>) => {
    const my = ++attempt.current;
    const cancelled = () => my !== attempt.current;
    setError(''); setPending(`${p} 계정 복구`);
    try {
      const info = await recover(cancelled);
      const mine = sessionAccount();
      if (cancelled() || !(await login(p, info, cancelled))) { if (sessionAccount() === mine) await endSession(); }
    } catch (e) {
      if (my === attempt.current) { setPending(null); setError(e instanceof Error && e.message ? e.message : '계정을 복구하지 못했어요.'); }
    }
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
        <SocialButtons onPress={start} />
      )}
      </View>
    </View>
  );
}
