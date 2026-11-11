// FS-GROUP-004 그룹원 관리, 005 탈퇴·권한 이전, 006 위치공유, 007 위치 조회, 008 재난 영향상태, 009 그룹 정보 수정
// C안 '탑승권': 그룹 정보 승차권 (구성원 명단), 영향상태 = 도장(확실하지 않은 상태는 점선)
import * as Clipboard from 'expo-clipboard';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { Coupon, Field, MONO, Perf, Stamp } from '../../components/ticket';
import { AppSwitch, Btn, Chip, Header, Icon, MapBox, Page, STALE_MIN, confirm, freshness, impactColor, knownImpact, notify, useTheme } from '../../components/ui';
import { CATEGORIES, IMPACT_ICON, Impact, ME, Member } from '../../data/mock';
import { useApp } from '../../store/AppContext';

// 영향지역 포함 → 판단 불가 → 위치 미제공 → 밖 (확인이 필요한 사람부터)
const IMPACT_ORDER: Record<Impact, number> = { '영향지역 포함': 0, '판단 불가': 1, '위치정보 미제공': 2, '영향지역 밖': 3 };
// 도장 글자 (짧게)
const STAMP: Record<Impact, string> = { '영향지역 포함': '영향지역', '영향지역 밖': '영향지역 밖', '위치정보 미제공': '위치 미제공', '판단 불가': '판단 불가' };

export default function GroupDetailScreen({ id }: { id: number }) {
  const { C, s } = useTheme();
  const { groups, setGroups, locConsent, location } = useApp();
  const [edit, setEdit] = useState<{ name: string; category: string } | null>(null); // 그룹 정보 수정 중
  const [picking, setPicking] = useState(false); // 관리자 탈퇴: 권한 넘길 사람 고르는 중
  const [managing, setManaging] = useState(false); // 관리자: 구성원 관리 모드 (행마다 권한 넘기기·제외 버튼)
  const [copied, setCopied] = useState(false);   // 초대 코드 복사 직후 2초간 '복사됨' 표시
  const g = groups.find(x => x.id === id);
  const me = g?.members.find(m => m.id === ME);
  if (!g || !me) return <View style={s.fill}><Header title="그룹" /><Text style={s.center}>그룹을 찾을 수 없습니다.</Text></View>;

  // 탈퇴 후 이 화면을 닫음. 주소로 바로 열어 뒤로 갈 화면이 없으면 그룹 목록으로
  const leaveScreen = () => (router.canGoBack() ? router.back() : router.replace('/group'));
  const update = (fn: (ms: Member[]) => Member[]) =>
    setGroups(gs => gs.map(x => (x.id === id ? { ...x, members: fn(x.members) } : x)));

  // 공유 위치는 항상 기기 GPS 위치. 직접 지정한 기준 위치는 공유하지 않음
  const toggleShare = (on: boolean) => {
    if (on && !locConsent) return notify('위치정보 이용 동의가 필요합니다', '내정보 › 서비스 설정 › 위치정보 이용 동의를 켜 주세요.');
    update(ms => ms.map(m => m.id !== ME ? m : on
      ? { ...m, sharing: true, place: location, updatedMin: 0, impact: '판단 불가' } // 지금 기준 위치 (목업 고정값 아님)
      : { ...m, sharing: false, place: undefined, updatedMin: undefined, impact: '위치정보 미제공' }));
  };
  const kick = (m: Member) => confirm('구성원 제외', `${m.name}님을 그룹에서 제외할까요?`, '제외',
    () => update(ms => ms.filter(x => x.id !== m.id)));
  const transfer = (m: Member) => confirm('관리자 권한 이전', `${m.name}님에게 관리자 권한을 넘길까요?`, '이전',
    () => update(ms => ms.map(x => ({ ...x, admin: x.id === m.id }))));
  // 실제로는 서버가 권한 이전 + 탈퇴를 한 처리 단위로 반영. 목업은 내 목록에서만 제거
  const transferAndLeave = (m: Member) => confirm('권한 이전 후 탈퇴', `${m.name}님에게 관리자 권한을 넘기고 탈퇴할까요?`, '탈퇴', () => {
    leaveScreen();
    setGroups(gs => gs.filter(x => x.id !== id)); // 목록에서 제거 = 내 그룹 접근·위치공유 즉시 해제
  });
  const leave = () => {
    if (me.admin && g.members.length > 1) return setPicking(true);
    // ponytail: 마지막 1인 관리자 탈퇴 시 그룹 처리 정책은 명세상 미정 → 임시로 목록에서 제거
    confirm('그룹 탈퇴', me.admin ? `마지막 구성원이에요. ${g.name}에서 탈퇴할까요?` : `${g.name}에서 탈퇴할까요?`, '탈퇴', () => {
      leaveScreen();
      setGroups(gs => gs.filter(x => x.id !== id));
    });
  };
  const copyCode = async () => {
    await Clipboard.setStringAsync(g.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  const saveEdit = () => {
    if (!edit?.name.trim()) return notify('그룹 이름을 입력하세요');
    setGroups(gs => gs.map(x => (x.id === id ? { ...x, name: edit.name.trim(), category: edit.category } : x)));
    setEdit(null);
  };

  const sorted = [...g.members].sort((a, b) => IMPACT_ORDER[knownImpact(a)] - IMPACT_ORDER[knownImpact(b)]);
  const hits = sorted.filter(m => knownImpact(m) === '영향지역 포함').length;
  const check = sorted.filter(m => { const k = knownImpact(m); return k === '판단 불가' || k === '위치정보 미제공'; }).length;

  return (
    <View style={s.fill}>
      <Header title={g.name} />
      {/* 그룹 정보 승차권 머리 띠가 헤더와 같은 색이라 띠 없이 헤더 바로 아래로 띄워 둠 */}
      <Page>
        {/* 그룹 정보 한 장: 요약 칸 → 구성원(위험한 순) → 절취선 → 초대 코드 */}
        {/* 관리자는 머리 띠의 '구성원 관리'로 관리 모드를 켜고 끔. 평소에는 행에 상태 도장만 있어 줄이 고르게 맞음 */}
        <Coupon head="그룹 정보" headIcon="people" right={<View style={s.row}>
          <Text style={[s.small, { color: '#FFFFFF', fontWeight: '700' }]}>{g.category} · {g.members.length}/10</Text>
          {me.admin && g.members.length > 1 && (
            <Pressable onPress={() => setManaging(v => !v)} accessibilityRole="button" accessibilityState={{ expanded: managing }} hitSlop={8}
              accessibilityLabel={managing ? '구성원 관리 끝내기' : '구성원 관리 (권한 넘기기·제외)'}
              style={({ pressed }) => [{ minHeight: 32, paddingHorizontal: 10, borderRadius: 6, justifyContent: 'center' },
                managing ? { backgroundColor: '#FFFFFF' } : { borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.7)' }, pressed && s.pressed]}>
              <Text style={[s.small, { fontWeight: '800', color: managing ? C.navy : '#FFFFFF' }]}>{managing ? '완료' : '구성원 관리'}</Text>
            </Pressable>
          )}
        </View>}>
          <View style={[s.row, { gap: 12 }]}>
            <Field label="구성원" value={`${g.members.length}명`} mono />
            <Field label="영향지역 포함" value={`${hits}명`} mono color={hits ? C.danger : undefined} />
            <Field label="확인 필요" value={`${check}명`} mono />
          </View>
          <Perf />
          <View style={[s.row, { marginBottom: 2 }]}>
            <Text style={[s.small, { flex: 1 }]}>구성원 · 위험한 순 · 마지막 위치</Text>
            <Text style={s.small}>상태</Text>
          </View>
          {sorted.map((m, n) => {
            const k = knownImpact(m);
            // 오래된 위치로는 영향상태를 확정하지 않음
            const old = (m.updatedMin ?? 0) > STALE_MIN && (m.impact === '영향지역 포함' || m.impact === '영향지역 밖');
            const vague = k === '판단 불가' || k === '위치정보 미제공';
            return (
              <View key={m.id} style={[s.row, { paddingVertical: 10, gap: 10 }, n > 0 && s.divTop]}>
                <View style={{ flex: 1 }}>
                  <View style={s.row}>
                    <Text style={[s.bold, { fontWeight: '900' }]}>{m.name}</Text>
                    {m.admin && <><Icon name="ribbon" size={16} color={C.warning} /><Text style={s.small}>관리자</Text></>}
                  </View>
                  <Text style={[s.muted, { marginTop: 0 }]}>{m.sharing ? `${m.place} · ${freshness(m.updatedMin)}` : '위치공유 OFF'}</Text>
                  {old && <Text style={[s.small, { color: C.warning }]}>{m.impact} · 오래된 위치 기준, 확인 필요</Text>}
                </View>
                {/* 관리 모드: 상태 도장 자리에 동작 버튼 (나 자신은 그대로 도장). 제외·권한 이전은 확인 후 처리 */}
                {managing && m.id !== ME ? (
                  <View style={[s.row, { gap: 6 }]}>
                    <Pressable onPress={() => transfer(m)} accessibilityRole="button" accessibilityLabel={`${m.name}님에게 관리자 권한 넘기기`}
                      style={({ pressed }) => [{ minHeight: 40, paddingHorizontal: 10, borderRadius: 8, borderWidth: 1.5, borderColor: C.tabOn, justifyContent: 'center' }, pressed && s.pressed]}>
                      <Text style={[s.small, { fontWeight: '800', color: C.tabOn }]}>관리자로</Text>
                    </Pressable>
                    <Pressable onPress={() => kick(m)} accessibilityRole="button" accessibilityLabel={`${m.name}님을 그룹에서 제외`}
                      style={({ pressed }) => [{ minHeight: 40, paddingHorizontal: 10, borderRadius: 8, borderWidth: 1.5, borderColor: C.danger, justifyContent: 'center' }, pressed && s.pressed]}>
                      <Text style={[s.small, { fontWeight: '800', color: C.danger }]}>제외</Text>
                    </Pressable>
                  </View>
                ) : (
                  <Stamp text={STAMP[k]} icon={IMPACT_ICON[k]} color={vague ? C.sub : impactColor(C, k)} dashed={vague} />
                )}
              </View>
            );
          })}
          <Perf />
          <View style={[s.row, { gap: 12 }]}>
            <View style={{ flex: 1 }}>
              <Text style={s.small}>초대 코드</Text>
              <Text style={[s.h2, { fontFamily: MONO, color: C.carbon, letterSpacing: 1 }]} selectable>{g.code}</Text>
            </View>
            <Pressable onPress={copyCode} hitSlop={8} accessibilityRole="button" accessibilityLabel="초대 코드 복사"
              style={({ pressed }) => [s.row, { gap: 4, minHeight: 44, paddingHorizontal: 14, borderRadius: 10, backgroundColor: copied ? C.success : C.navy }, pressed && s.pressed]}>
              <Icon name={copied ? 'checkmark' : 'copy-outline'} size={16} color="#FFFFFF" />
              <Text style={[s.bold, { color: '#FFFFFF', fontSize: 15 }]}>{copied ? '복사됨' : '코드 복사'}</Text>
            </Pressable>
          </View>
          {me.admin && !edit && (
            <Pressable onPress={() => setEdit({ name: g.name, category: g.category })} accessibilityRole="button"><Text style={[s.link, { marginLeft: 0, marginTop: 4 }]}>그룹 정보 수정</Text></Pressable>
          )}
        </Coupon>
        {me.admin && edit && (
          <Coupon head="그룹 정보 수정" headIcon="create-outline">
            <TextInput accessibilityLabel="그룹 이름" style={s.input} value={edit.name} onChangeText={name => setEdit({ ...edit, name })} maxLength={20} placeholder="그룹 이름" placeholderTextColor={C.sub} />
            <View style={[s.row, { flexWrap: 'wrap' }]}>{CATEGORIES.map(c => <Chip key={c} text={c} on={edit.category === c} onPress={() => setEdit({ ...edit, category: c })} />)}</View>
            <View style={s.row}>
              <View style={{ flex: 1 }}><Btn title="취소" outline color={C.tabOn} onPress={() => setEdit(null)} /></View>
              <View style={{ flex: 1 }}><Btn title="저장" color={C.navy} onPress={saveEdit} /></View>
            </View>
          </Coupon>
        )}
        <Coupon>
          <View style={s.row}>
            <Icon name="navigate-circle-outline" size={22} color={C.tabOn} />
            <Text style={[s.bold, { flex: 1 }]}>내 위치 공유</Text>
            <AppSwitch accessibilityLabel="내 위치 공유" value={me.sharing} onValueChange={toggleShare} />
          </View>
          <Text style={s.muted}>OFF 시 즉시 그룹원에게 위치 제공이 중단됩니다.</Text>
        </Coupon>
        <MapBox><Text style={s.muted}>그룹원 위치 지도 (공유 ON 구성원만 표시)</Text></MapBox>
        {picking ? (
          <Coupon head="관리자 권한을 넘길 구성원 선택" headIcon="ribbon">
            <Text style={s.muted}>관리자는 권한을 넘긴 뒤에만 탈퇴할 수 있어요.</Text>
            {g.members.filter(m => m.id !== ME).map(m => (
              <Pressable key={m.id} onPress={() => transferAndLeave(m)} accessibilityRole="button" accessibilityLabel={`${m.name}님에게 넘기고 탈퇴`} style={({ pressed }) => [s.row, { paddingVertical: 10, minHeight: 48 }, pressed && s.pressed]}>
                <Icon name="person-circle-outline" size={24} color={C.sub} />
                <Text style={[s.body, { flex: 1 }]}>{m.name}</Text>
                <Icon name="chevron-forward" size={18} color={C.sub} />
              </Pressable>
            ))}
            <Btn title="취소" outline color={C.tabOn} onPress={() => setPicking(false)} />
          </Coupon>
        ) : (
          <Btn title="그룹 탈퇴" icon="exit-outline" color={C.danger} outline onPress={leave} />
        )}
      </Page>
    </View>
  );
}
