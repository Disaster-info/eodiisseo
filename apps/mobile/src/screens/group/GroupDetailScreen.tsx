// FS-GROUP-004 그룹원 관리, 005 탈퇴·권한 이전, 006 위치공유, 007 위치 조회, 008 재난 영향상태, 009 그룹 정보 수정
// C안 '탑승권': 그룹 정보 승차권 (구성원 명단), 영향상태 = 도장(확실하지 않은 상태는 점선)
import * as Clipboard from 'expo-clipboard';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { Coupon, Field, MONO, Perf, Stamp } from '../../components/ticket';
import { AppSwitch, Btn, Chip, Header, Icon, MapBox, Page, STALE_MIN, ageMin, confirm, freshness, impactColor, knownImpact, notify, useTheme } from '../../components/ui';
import { errText, groupApi } from '../../data/server';
import { CATEGORIES, IMPACT_ICON, Impact, Member } from '../../data/types';
import { useApp } from '../../store/AppContext';

// 영향지역 포함 → 판단 불가 → 위치 미제공 → 밖 (확인이 필요한 사람부터)
const IMPACT_ORDER: Record<Impact, number> = { '영향지역 포함': 0, '판단 불가': 1, '위치정보 미제공': 2, '영향지역 밖': 3 };
// 도장 글자 (짧게)
const STAMP: Record<Impact, string> = { '영향지역 포함': '영향지역', '영향지역 밖': '영향지역 밖', '위치정보 미제공': '위치 미제공', '판단 불가': '판단 불가' };

export default function GroupDetailScreen({ id }: { id: string }) {
  const { C, s } = useTheme();
  const { groups, groupsState, reloadGroups, member, locConsent, gpsRegion } = useApp();
  const [busy, setBusy] = useState(false); // 서버 요청 중 (두 번 누름 방지)
  const [edit, setEdit] = useState<{ name: string; category: string } | null>(null); // 그룹 정보 수정 중
  const [picking, setPicking] = useState(false); // 관리자 탈퇴: 권한 넘길 사람 고르는 중
  const [managing, setManaging] = useState(false); // 관리자: 구성원 관리 모드 (행마다 권한 넘기기·제외 버튼)
  const [copied, setCopied] = useState(false);   // 초대 코드 복사 직후 2초간 '복사됨' 표시
  useEffect(() => { // '복사됨' 2초 뒤 해제. 화면을 떠나면 타이머도 정리
    if (!copied) return;
    const t = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(t);
  }, [copied]);
  const g = groups.find(x => x.id === id);
  const me = g?.members.find(m => m.id === member?.id);
  if (!g || !me) return <View style={s.fill}><Header title="그룹" /><Text style={s.center}>{groupsState === 'loading' ? '그룹 정보를 불러오는 중…' : groupsState === 'error' ? '그룹 정보를 불러오지 못했어요.' : '그룹을 찾을 수 없습니다.'}</Text></View>;

  // 탈퇴 후 이 화면을 닫음. 주소로 바로 열어 뒤로 갈 화면이 없으면 그룹 목록으로
  const leaveScreen = () => (router.canGoBack() ? router.back() : router.replace('/group'));
  // 서버에 반영 → 그룹을 다시 받아 화면을 맞춤. 실패하면 서버 문구로 안내 (화면 값은 서버 값 그대로)
  const run = async (title: string, work: () => Promise<unknown>, after?: () => void) => {
    if (busy) return;
    setBusy(true);
    try {
      await work();
      after?.();
    } catch (e) {
      notify(title, errText(e));
    } finally {
      setBusy(false);
      reloadGroups();
    }
  };

  // 공유 위치는 항상 기기 GPS 위치. 직접 지정한 기준 위치는 공유하지 않음
  const toggleShare = (on: boolean) => {
    if (on && !locConsent) return notify('위치정보 이용 동의가 필요합니다', '내정보 › 서비스 설정 › 위치정보 이용 동의를 켜 주세요.');
    // GPS 로 확인한 위치가 없으면(확인 중·권한 거부·실패) 공유하지 않음 — 직접 지정한 지역을 내 위치처럼 보내지 않게
    if (on && !gpsRegion) return notify('현재 위치를 확인하지 못했어요', '휴대폰의 위치 권한을 확인하고 잠시 후 다시 시도해 주세요.');
    run('위치 공유를 바꾸지 못했어요', () => groupApi.sharing(id, on));
  };
  // 제외·권한 이전은 지금 관리자일 때만 (확인 창이 떠 있는 사이 권한이 바뀌어도 처리 시점에 다시 확인)
  // 권한 확인은 서버가 처리 시점에 다시 함 (확인 창이 떠 있는 사이 권한이 바뀌어도 안전)
  const kick = (m: Member) => confirm('구성원 제외', `${m.name}님을 그룹에서 제외할까요?`, '제외',
    () => run('구성원을 제외하지 못했어요', () => groupApi.exclude(id, m.id)));
  const transfer = (m: Member) => confirm('관리자 권한 이전', `${m.name}님에게 관리자 권한을 넘길까요?`, '이전',
    () => run('권한을 넘기지 못했어요', () => groupApi.transfer(id, m.id), () => { setManaging(false); setEdit(null); })); // 권한을 넘기면 관리·수정 모드 종료
  // 관리 모드는 관리자이고 나 말고 구성원이 있을 때만 (권한을 잃거나 혼자 남으면 버튼이 사라짐)
  const canManage = me.admin && g.members.length > 1;
  const managingNow = managing && canManage;
  // ponytail: 백엔드에 '권한 이전 + 탈퇴' 한 번에 하는 API 가 없어 두 번 요청. 이전 후 탈퇴가 실패하면 일반 구성원으로 남음 (안내함)
  const transferAndLeave = (m: Member) => confirm('권한 이전 후 탈퇴', `${m.name}님에게 관리자 권한을 넘기고 탈퇴할까요?`, '탈퇴',
    () => run('탈퇴하지 못했어요', async () => {
      await groupApi.transfer(id, m.id); // 여기서 실패하면 아무것도 바뀌지 않음 (그대로 관리자)
      setPicking(false);
      try {
        await groupApi.leave(id);
      } catch (e) {
        // 권한은 이미 넘어감 → 지금은 일반 구성원. 되돌릴 수 없으니 정확히 알리고, '그룹 탈퇴'를 다시 누르면 바로 탈퇴됨
        throw new Error(`관리자 권한은 ${m.name}님에게 넘겼지만 탈퇴는 처리되지 않았어요. '그룹 탈퇴'를 다시 눌러 주세요. (${errText(e)})`);
      }
    }, leaveScreen));
  const leave = () => {
    if (me.admin && g.members.length > 1) return setPicking(true);
    // 마지막 구성원인 관리자: 백엔드 정책상 탈퇴 대신 그룹 삭제
    confirm(me.admin ? '그룹 삭제' : '그룹 탈퇴', me.admin ? `마지막 구성원이에요. ${g.name} 그룹을 삭제할까요?` : `${g.name}에서 탈퇴할까요?`, me.admin ? '삭제' : '탈퇴',
      () => run('탈퇴하지 못했어요', () => (me.admin ? groupApi.remove(id) : groupApi.leave(id)), leaveScreen));
  };
  const copyCode = async () => {
    // 웹은 https 가 아니거나 권한이 없으면 복사가 실패함 → 코드를 직접 보여 줌
    if (!(await Clipboard.setStringAsync(g.code).catch(() => false))) return notify('복사하지 못했어요', `초대 코드: ${g.code}`);
    setCopied(true);
  };
  const saveEdit = () => {
    if (!edit?.name.trim()) return notify('그룹 이름을 입력하세요');
    run('그룹 정보를 저장하지 못했어요', () => groupApi.edit(id, edit.name.trim(), edit.category), () => setEdit(null));
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
          {canManage && (
            <Pressable onPress={() => setManaging(v => !v)} accessibilityRole="button" accessibilityState={{ expanded: managingNow }} hitSlop={8}
              accessibilityLabel={managingNow ? '구성원 관리 끝내기' : '구성원 관리 (권한 넘기기·제외)'}
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
            const old = (ageMin(m.updatedAt) ?? 0) > STALE_MIN && (m.impact === '영향지역 포함' || m.impact === '영향지역 밖');
            const vague = k === '판단 불가' || k === '위치정보 미제공';
            return (
              <View key={m.id} style={[s.row, { paddingVertical: 10, gap: 10 }, n > 0 && s.divTop]}>
                <View style={{ flex: 1 }}>
                  <View style={s.row}>
                    <Text style={[s.bold, { fontWeight: '900' }]}>{m.name}</Text>
                    {m.admin && <><Icon name="ribbon" size={16} color={C.warning} /><Text style={s.small}>관리자</Text></>}
                  </View>
                  <Text style={[s.muted, { marginTop: 0 }]}>{m.sharing ? freshness(m.updatedAt) : '위치공유 OFF'}</Text>
                  {old && <Text style={[s.small, { color: C.warning }]}>{m.impact} · 오래된 위치 기준, 확인 필요</Text>}
                </View>
                {/* 관리 모드: 상태 도장 자리에 동작 버튼 (나 자신은 그대로 도장). 제외·권한 이전은 확인 후 처리 */}
                {managingNow && m.id !== me.id ? (
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
            {g.members.filter(m => m.id !== me.id).map(m => (
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
