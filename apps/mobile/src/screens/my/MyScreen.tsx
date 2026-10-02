// FS-AUTH-006 회원정보 조회·프로필(이름) 수정, 003 소셜 계정 연결, 005 로그아웃, 007 회원 탈퇴 / FS-LOC-001 동의 철회 / FS-OPS 진입
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, Switch, Text, TextInput, View } from 'react-native';
import { Btn, C, Card, Chip, Icon, IconName, Label, Page, confirm, notify, s } from '../../components/ui';
import { Provider } from '../../data/mock';
import { ThemeMode, useApp } from '../../store/AppContext';

const PROVIDERS: Provider[] = ['Google', 'Kakao'];
const THEMES: { mode: ThemeMode; label: string }[] = [
  { mode: 'system', label: '시스템 설정' }, { mode: 'light', label: '라이트' }, { mode: 'dark', label: '다크' },
];
const PROVIDER_ICON: Record<Provider, IconName> = { Google: 'logo-google', Kakao: 'chatbubble' };

// 설정 목록의 한 줄: 아이콘 + 제목 + (부가설명) + 오른쪽 요소
const Row = ({ icon, title, sub, right, onPress }: { icon: IconName; title: string; sub?: string; right?: React.ReactNode; onPress?: () => void }) => (
  <Pressable onPress={onPress} disabled={!onPress} style={({ pressed }) => [s.row, { gap: 12, paddingVertical: 10 }, pressed && s.pressed]}>
    <Icon name={icon} size={22} color={C.sub} />
    <View style={{ flex: 1 }}>
      <Text style={s.body}>{title}</Text>
      {!!sub && <Text style={s.muted}>{sub}</Text>}
    </View>
    {right ?? (onPress && <Icon name="chevron-forward" size={18} color={C.sub} />)}
  </Pressable>
);

export default function MyScreen() {
  const { member, rename, provider, linked, setLinked, noti, locConsent, setLocConsent, location, areas, areaNames,
    logout, expire, withdraw, adminBlockingGroups, theme, setTheme } = useApp();
  const [nameDraft, setNameDraft] = useState<string | null>(null); // null = 보기 모드
  if (!member) return null;

  const saveName = () => {
    const name = (nameDraft ?? '').trim();
    if (name.length < 2) return notify('이름은 2자 이상 입력해 주세요');
    rename(name);
    setNameDraft(null);
  };

  // FS-AUTH-003: 추가 인증을 거친 뒤에만 연결 (이메일이 같아도 자동 통합하지 않음)
  const connect = (p: Provider) => confirm('소셜 계정 연결', `${p} 계정으로 추가 인증을 진행합니다.\n이미 다른 회원에 연결된 계정은 연결할 수 없습니다.`, '인증하기',
    () => { setLinked(l => [...l, p]); notify('연결 완료', `${p} 계정이 연결되었습니다.`); });

  const requestWithdraw = () => {
    if (adminBlockingGroups.length)
      return notify('탈퇴 전 관리자 권한 이전 필요', `${adminBlockingGroups.map(g => g.name).join(', ')} 그룹의 관리자 권한을 다른 구성원에게 먼저 넘겨주세요.`);
    confirm('회원 탈퇴', '로그인 정보, 관심지역, 체크리스트, 알림 설정, 위치정보가 삭제되고 모든 그룹에서 탈퇴됩니다.\n이 작업은 되돌릴 수 없습니다.', '탈퇴', withdraw);
  };

  return (
    <Page>
      <Card>
        <View style={[s.row, { gap: 14 }]}>
          <Icon name="person-circle" size={56} color={C.primary} />
          <View style={{ flex: 1 }}>
            <Text style={s.h2}>{member.name}</Text>
            <Text style={s.body}>{member.email}</Text>
            <Text style={s.muted}>{provider} 계정으로 로그인 · 가입일 {member.joinedAt}</Text>
          </View>
          {nameDraft === null && (
            <Pressable onPress={() => setNameDraft(member.name)} hitSlop={8} accessibilityRole="button" accessibilityLabel="프로필 수정"
              style={({ pressed }) => [{ padding: 8, borderRadius: 999, backgroundColor: C.primarySoft, alignSelf: 'flex-start' }, pressed && s.pressed]}>
              <Icon name="create-outline" size={18} color={C.primary} />
            </Pressable>
          )}
        </View>
        {nameDraft !== null && (
          <View style={{ marginTop: 12 }}>
            <View style={s.divider} />
            <Text style={s.bold}>이름</Text>
            <TextInput style={s.input} value={nameDraft} onChangeText={setNameDraft} maxLength={10} autoFocus
              placeholder="2~10자" placeholderTextColor={C.sub} returnKeyType="done" onSubmitEditing={saveName} />
            <Text style={s.bold}>이메일</Text>
            <Text style={[s.muted, { marginBottom: 12 }]}>{member.email} · {provider} 계정 정보라 여기서 바꿀 수 없어요.</Text>
            <View style={s.row}>
              <View style={{ flex: 1 }}><Btn title="취소" outline onPress={() => setNameDraft(null)} /></View>
              <View style={{ flex: 1 }}><Btn title="저장" onPress={saveName} /></View>
            </View>
          </View>
        )}
      </Card>

      <Card>
        <Label icon="link" text="소셜 계정 연결" />
        {PROVIDERS.map(p => (
          <Row key={p} icon={PROVIDER_ICON[p]} title={p}
            right={p === provider ? <Text style={s.muted}>로그인 계정</Text>
              : linked.includes(p) ? <Text style={[s.muted, { color: C.success }]}>연결됨</Text>
              : <Pressable onPress={() => connect(p)}><Text style={s.link}>연결하기</Text></Pressable>} />
        ))}
      </Card>

      <Card>
        <Label icon="settings" text="서비스 설정" />
        <Row icon="location-outline" title="위치정보 이용 동의" sub="OFF 하면 모든 그룹에서 내 위치공유가 중단됩니다."
          right={<Switch value={locConsent} onValueChange={setLocConsent} trackColor={{ true: C.primary }} />} />
        <Row icon="notifications-outline" title="재난 알림 설정" sub={noti.on ? `${noti.minLevel} 이상 · 야간 ${noti.night ? '허용' : '차단'}` : '알림 꺼짐'}
          onPress={() => router.push('/noti')} />
        <Row icon="contrast-outline" title="화면 테마" />
        <View style={[s.row, { flexWrap: 'wrap', marginLeft: 34 }]}>
          {THEMES.map(t => <Chip key={t.mode} text={t.label} on={theme === t.mode} onPress={() => setTheme(t.mode)} />)}
        </View>
        <Row icon="pin-outline" title="기준 위치 직접 지정" sub={`현재: ${location}`} onPress={() => router.push('/location')} />
        <Row icon="star-outline" title="관심지역 관리" sub={areas.length ? areas.map(a => areaNames[a] ?? a).join(', ') : '없음'} onPress={() => router.push('/areas')} />
      </Card>

      {member.operator && (
        <Card><Row icon="construct-outline" title="운영 관리" sub="API 수집 상태 · 재난 데이터 · 오류 로그" onPress={() => router.push('/ops')} /></Card>
      )}

      <Btn title="로그아웃" icon="log-out-outline" outline onPress={() => confirm('로그아웃', '로그아웃할까요?', '로그아웃', logout)} />
      <Pressable onPress={requestWithdraw}>
        <Text style={[s.muted, { textAlign: 'center', padding: 8 }]}>회원 탈퇴</Text>
      </Pressable>
      {/* 시연용: 인증 만료 상황 재현 (개발 모드에서만 보임) */}
      {__DEV__ && <Pressable onPress={expire}><Text style={[s.muted, { textAlign: 'center' }]}>[개발용] 인증 만료 시뮬레이션</Text></Pressable>}
    </Page>
  );
}
