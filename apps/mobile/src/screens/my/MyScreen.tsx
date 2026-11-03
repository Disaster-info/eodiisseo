// FS-AUTH-006 회원정보 조회·프로필(이름) 수정, 003 소셜 계정 연결, 005 로그아웃, 007 회원 탈퇴 / FS-LOC-001 동의 철회 / FS-OPS 진입
// 배치: 프로필 + 상태 요약 태그 → 위치·알림 → 화면 → 계정 → 로그아웃·탈퇴 (비슷한 설정끼리 묶음, 모든 행 같은 높이)
import { router } from 'expo-router';
import { Children, ReactNode, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { AppSwitch, Badge, Btn, Icon, IconName, Page, Segmented, confirm, notify, useTheme } from '../../components/ui';
import { Provider } from '../../data/mock';
import { ThemeMode, useApp } from '../../store/AppContext';

const PROVIDERS: Provider[] = ['Google', 'Kakao'];
const THEMES: { mode: ThemeMode; label: string }[] = [
  { mode: 'system', label: '시스템' }, { mode: 'light', label: '라이트' }, { mode: 'dark', label: '다크' },
];
const PROVIDER_ICON: Record<Provider, IconName> = { Google: 'logo-google', Kakao: 'chatbubble' };

// 설정 목록의 한 줄: 아이콘 + 제목 + (부가설명) + 오른쪽(값·스위치 등)
// value: 현재 값 (오른쪽 회색 글자 + 화살표). danger: 탈퇴처럼 되돌릴 수 없는 동작 (빨간 아이콘·제목)
function Row({ icon, title, sub, value, right, onPress, danger }:
  { icon: IconName; title: string; sub?: string; value?: string; right?: ReactNode; onPress?: () => void; danger?: boolean }) {
  const { C, s } = useTheme();
  return (
    <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={value ? `${title}, ${value}` : undefined}
      style={({ pressed }) => [s.row, { gap: 12, paddingVertical: 8, minHeight: 52 }, pressed && s.pressed]}>
      <Icon name={icon} size={22} color={danger ? C.danger : C.sub} />
      <View style={{ flex: 1 }}>
        <Text style={[s.body, danger && { color: C.danger, fontWeight: '700' }]}>{title}</Text>
        {!!sub && <Text style={[s.small, { marginTop: 1 }]}>{sub}</Text>}
      </View>
      {!!value && <Text style={[s.muted, { marginTop: 0, flexShrink: 1, textAlign: 'right' }]} numberOfLines={1}>{value}</Text>}
      {right ?? (onPress && <Icon name="chevron-forward" size={18} color={C.sub} />)}
    </Pressable>
  );
}

// 묶음: 제목(선택) + 카드 안 행 사이에 얇은 구분선
function Group({ title, children }: { title?: string; children: ReactNode }) {
  const { s } = useTheme();
  const rows = Children.toArray(children).filter(Boolean);
  return (
    <View>
      {!!title && <Text style={[s.small, { fontWeight: '700', marginLeft: 16, marginBottom: 6 }]}>{title}</Text>}
      <View style={[s.card, { paddingVertical: 2 }]}>
        {rows.map((r, i) => <View key={i} style={i > 0 && s.divTop}>{r}</View>)}
      </View>
    </View>
  );
}

export default function MyScreen() {
  const { C, s } = useTheme();
  const { member, rename, provider, linked, setLinked, noti, locConsent, setLocConsent, isManual, location, areas,
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

  // FS-LOC-001: 철회는 모든 그룹 공유를 끄므로 확인 후 처리. 다시 동의해도 그룹 공유는 자동으로 켜지지 않음
  const toggleConsent = (on: boolean) => on ? setLocConsent(true)
    : confirm('위치정보 동의 철회', `모든 그룹에서 내 위치공유가 즉시 중단돼요. 다시 동의해도 그룹별로 직접 켜야 해요.${isManual ? '' : '\n철회 후 기준 위치를 직접 지정해야 해요.'}`, '철회', () => setLocConsent(false));

  const requestWithdraw = () => {
    if (adminBlockingGroups.length)
      return notify('탈퇴 전 관리자 권한 이전 필요', `${adminBlockingGroups.map(g => g.name).join(', ')} 그룹의 관리자 권한을 다른 구성원에게 먼저 넘겨주세요.`);
    confirm('회원 탈퇴', '로그인 정보, 관심지역, 체크리스트, 알림 설정, 위치정보가 삭제되고 모든 그룹에서 탈퇴됩니다.\n이 작업은 되돌릴 수 없습니다.', '탈퇴', withdraw);
  };

  const notiSummary = noti.on ? `${noti.minLevel} 이상 · 야간 ${noti.night ? '허용' : '차단'}` : '꺼짐';

  return (
    <Page>
      {/* 프로필 + 상태 요약 */}
      <View style={s.card}>
        <View style={[s.row, { gap: 14 }]}>
          <View style={[s.rim, { width: 52, height: 52, borderRadius: 26, backgroundColor: C.fill, justifyContent: 'center', alignItems: 'center' }]}>
            <Text style={[s.h2, { color: C.onSign, fontWeight: '800' }]} maxFontSizeMultiplier={1.3}>{member.name.slice(0, 1)}</Text>
          </View>
          <View style={{ flex: 1 }}>
            <Text style={s.h2}>{member.name}</Text>
            {/* 로그인 제공자는 아래 '계정' 묶음에 표시 (여기서 같이 쓰면 이메일이 잘림) */}
            {!!member.email && <Text style={[s.muted, { marginTop: 1 }]} numberOfLines={1}>{member.email}</Text>}
          </View>
          {nameDraft === null && (
            <Pressable onPress={() => setNameDraft(member.name)} hitSlop={8} accessibilityRole="button" accessibilityLabel="프로필 편집"
              style={({ pressed }) => [s.rim, { minHeight: 36, paddingHorizontal: 14, borderRadius: 999, backgroundColor: C.primarySoft, justifyContent: 'center' }, pressed && s.pressed]}>
              <Text style={[s.small, { color: C.primary, fontWeight: '700' }]}>편집</Text>
            </Pressable>
          )}
        </View>
        {nameDraft === null ? (
          <View style={[s.row, { flexWrap: 'wrap', gap: 6, marginTop: 12 }]} accessible accessibilityLabel={`위치 동의 ${locConsent ? '켜짐' : '꺼짐'}, 알림 ${notiSummary}, 관심지역 ${areas.length}곳`}>
            <Badge text={`위치 동의 ${locConsent ? '켜짐' : '꺼짐'}`} color={locConsent ? C.success : C.sub} />
            <Badge text={noti.on ? `알림 ${noti.minLevel} 이상` : '알림 꺼짐'} color={noti.on ? C.primary : C.sub} />
            <Badge text={`관심지역 ${areas.length}곳`} color={C.primary} />
          </View>
        ) : (
          <View style={{ marginTop: 12 }}>
            <View style={s.divider} />
            <Text style={s.bold}>이름</Text>
            <TextInput accessibilityLabel="이름" style={s.input} value={nameDraft} onChangeText={setNameDraft} maxLength={10} autoFocus
              placeholder="2~10자" placeholderTextColor={C.sub} returnKeyType="done" onSubmitEditing={saveName} />
            <Text style={s.bold}>이메일</Text>
            <Text style={[s.muted, { marginBottom: 12 }]}>{member.email ? `${member.email} · ` : ''}{provider} 계정 정보라 여기서 바꿀 수 없어요.</Text>
            <View style={s.row}>
              <View style={{ flex: 1 }}><Btn title="취소" outline onPress={() => setNameDraft(null)} /></View>
              <View style={{ flex: 1 }}><Btn title="저장" onPress={saveName} /></View>
            </View>
          </View>
        )}
      </View>

      <Group title="위치 · 알림">
        <Row icon="location-outline" title="위치정보 이용 동의" sub="끄면 동의 철회 · 그룹 위치공유 중단"
          right={<AppSwitch accessibilityLabel="위치정보 이용 동의" value={locConsent} onValueChange={toggleConsent} />} />
        <Row icon="pin-outline" title="기준 위치" value={location} onPress={() => router.push('/location')} />
        <Row icon="star-outline" title="관심지역" value={`${areas.length}곳`} onPress={() => router.push('/areas')} />
        <Row icon="notifications-outline" title="재난 알림" value={notiSummary} onPress={() => router.push('/noti')} />
      </Group>

      <Group title="화면">
        {/* 고정 폭 대신 제목 아래 한 줄 전체 → 좁은 화면·큰 글씨에서도 제목이 눌리지 않음 */}
        <View style={{ paddingVertical: 10, gap: 8 }}>
          <View style={[s.row, { gap: 12 }]}>
            <Icon name="contrast-outline" size={22} color={C.sub} />
            <Text style={[s.body, { flex: 1 }]}>화면 테마</Text>
          </View>
          <Segmented label="화면 테마" items={THEMES.map(t => ({ value: t.mode, label: t.label }))} value={theme} onChange={setTheme} />
        </View>
      </Group>

      <Group title="계정">
        {PROVIDERS.map(p => (
          <Row key={p} icon={PROVIDER_ICON[p]} title={p}
            right={p === provider ? <Text style={[s.muted, { marginTop: 0 }]}>로그인 계정</Text>
              : linked.includes(p) ? <Text style={[s.muted, { marginTop: 0, color: C.success }]}>연결됨</Text>
              : <Pressable onPress={() => connect(p)} accessibilityRole="button" accessibilityLabel={`${p} 계정 연결하기`}><Text style={s.link}>연결하기</Text></Pressable>} />
        ))}
        {member.operator && <Row icon="construct-outline" title="운영 관리" sub="API 수집 상태 · 재난 데이터 · 오류 로그" onPress={() => router.push('/ops')} />}
      </Group>

      <Group>
        <Row icon="log-out-outline" title="로그아웃" onPress={() => confirm('로그아웃', '로그아웃할까요?', '로그아웃', logout)} right={false} />
        <Row icon="person-remove-outline" title="회원 탈퇴" sub="모든 데이터 삭제 · 되돌릴 수 없어요" onPress={requestWithdraw} danger />
      </Group>

      {!!member.joinedAt && <Text style={[s.small, { textAlign: 'center', marginTop: 4 }]}>가입일 {member.joinedAt}</Text>}
      {/* 시연용: 인증 만료 상황 재현 (개발 모드에서만 보임) */}
      {__DEV__ && <Pressable onPress={expire} style={{ minHeight: 44, justifyContent: 'center' }}><Text style={[s.small, { textAlign: 'center' }]}>[개발용] 인증 만료 시뮬레이션</Text></Pressable>}
    </Page>
  );
}
