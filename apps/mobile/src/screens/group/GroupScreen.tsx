// FS-GROUP-001 생성, 002 목록, 003 초대 코드 참여 — C안 '탑승권': 그룹 한 개 = 승차권 한 장
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Text, TextInput, View } from 'react-native';
import { Coupon, Field, MONO, Perf } from '../../components/ticket';
import { Btn, Chip, Icon, Page, notify, knownImpact, useTheme } from '../../components/ui';
import { errText, groupApi } from '../../data/server';
import { CATEGORIES, Group } from '../../data/types';
import { useApp } from '../../store/AppContext';

const MAX_CREATE = 3;
const MAX_MEMBERS = 10;
const hit = (g: Group) => g.members.filter(m => knownImpact(m) === '영향지역 포함').length;

export default function GroupScreen() {
  const { C, s } = useTheme();
  const { groups, groupsState, reloadGroups } = useApp();
  const [name, setName] = useState('');
  const [category, setCategory] = useState('가족');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false); // 서버 요청 중 (두 번 누름 방지)

  // 백엔드 제한: 참여(만든 것 포함) 그룹 최대 3개. 서버가 최종 판단하고 여기서는 미리 안내만
  const create = async () => {
    if (busy) return;
    if (!name.trim()) return notify('그룹 이름을 입력하세요');
    if (groups.length >= MAX_CREATE) return notify(`그룹은 최대 ${MAX_CREATE}개까지 참여할 수 있어요`);
    setBusy(true);
    try {
      const r = await groupApi.create(name.trim(), category);
      setName('');
      await reloadGroups();
      notify('그룹을 만들었어요', `초대 코드 ${r.inviteCode} 를 가족·친구에게 알려주세요.`);
    } catch (e) {
      notify('그룹을 만들지 못했어요', errText(e));
    } finally { setBusy(false); }
  };

  const join = async () => {
    if (busy) return;
    const c = code.replace(/[^A-Z0-9]/gi, '').toUpperCase(); // 하이픈·공백은 빼고 (서버 코드는 영문·숫자 6자 이상)
    if (!c) return notify('초대 코드를 입력하세요');
    if (groups.some(g => g.code === c)) return notify('이미 참여 중인 그룹입니다');
    setBusy(true);
    try {
      const r = await groupApi.join(c);
      setCode('');
      await reloadGroups();
      notify('참여 완료', `${r.name} 그룹에 참여했어요`);
    } catch (e) {
      notify('참여 실패', errText(e));
    } finally { setBusy(false); }
  };

  return (
    // 띠는 제목 줄까지만: 그룹 승차권 머리 띠가 상단 띠와 같은 색이라 겹치면 한 덩어리로 보여서, 띠 바로 아래에 둠
    <Page band={52}>
      <Text style={[s.section, { color: '#FFFFFF', marginTop: 0, marginBottom: 8 }]} accessibilityRole="header">참여 중인 그룹 {groups.length}개</Text>
      {groupsState === 'loading' && groups.length === 0 && <ActivityIndicator color="#FFFFFF" accessibilityLabel="그룹을 불러오는 중" />}
      {groupsState === 'error' && (
        <Coupon><Text style={s.muted}>그룹 정보를 불러오지 못했어요. 그룹원의 위치·영향상태가 최신이 아닐 수 있어요.</Text>
          <Btn title="다시 시도" icon="refresh" outline color={C.tabOn} onPress={reloadGroups} /></Coupon>
      )}
      {groupsState === 'ok' && groups.length === 0 && (
        <Coupon dashed><Text style={s.muted}>참여 중인 그룹이 없어요. 그룹을 만들거나 초대 코드로 참여하세요.</Text></Coupon>
      )}
      {/* 그룹 = 탑승권 한 장: 머리 띠(이름·분류·관리자) → 칸(구성원·영향지역·확인 필요) → 절취선 → 초대 코드 */}
      {groups.map(g => {
        const n = hit(g);
        const check = g.members.filter(m => { const k = knownImpact(m); return k === '판단 불가' || k === '위치정보 미제공'; }).length;
        const admin = g.admin;
        return (
          <Coupon key={g.id} head={g.name} headIcon="people" onPress={() => router.push(`/groups/${g.id}`)}
            accessibilityLabel={`${g.name}, ${g.category}${admin ? ', 관리자' : ''}, 구성원 ${g.members.length}명, 영향지역 포함 ${n}명. 자세히`}
            right={<View style={s.row}>
              <Text style={[s.small, { color: '#FFFFFF', fontWeight: '700' }]}>{g.category}</Text>
              {admin && <Text style={[s.small, { color: C.navy, backgroundColor: '#FFFFFF', fontWeight: '900', paddingHorizontal: 6, borderRadius: 4, overflow: 'hidden' }]}>관리자</Text>}
              <Icon name="chevron-forward" size={18} color="#FFFFFF" />
            </View>}>
            <View style={[s.row, { gap: 12 }]}>
              <Field label="구성원" value={`${g.members.length}/${MAX_MEMBERS}`} mono />
              <Field label="영향지역 포함" value={`${n}명`} mono color={n ? C.danger : undefined} />
              <Field label="확인 필요" value={`${check}명`} mono />
            </View>
            {!n && <Text style={s.small}>영향지역 포함 없음</Text>}
            <Perf />
            <View style={s.row}>
              <Text style={[s.small, { flex: 1 }]}>초대 코드</Text>
              <Text style={[s.bold, { fontFamily: MONO, color: C.carbon, letterSpacing: 1 }]}>{g.code}</Text>
            </View>
          </Coupon>
        );
      })}
      <Coupon head={`새 그룹 만들기 (참여 ${groups.length}/${MAX_CREATE})`} headIcon="add-circle">
        <TextInput accessibilityLabel="새 그룹 이름" style={s.input} placeholder="그룹 이름" placeholderTextColor={C.sub} value={name} onChangeText={setName} maxLength={20} />
        <View style={[s.row, { flexWrap: 'wrap' }]}>{CATEGORIES.map(c => <Chip key={c} text={c} on={category === c} onPress={() => setCategory(c)} />)}</View>
        <Btn title={busy ? '처리 중…' : '만들기'} icon="add" color={C.navy} onPress={create} />
      </Coupon>
      <Coupon head="초대 코드로 참여" headIcon="key">
        {/* 일부 안드로이드 키보드는 autoCapitalize 를 무시하므로 입력값을 직접 대문자로 변환 */}
        <TextInput accessibilityLabel="초대 코드" style={[s.input, { letterSpacing: 1, fontFamily: MONO }]} placeholder="예: AB23CD" placeholderTextColor={C.sub} value={code}
          onChangeText={t => setCode(t.toUpperCase())} autoCapitalize="characters" autoCorrect={false} maxLength={12} />
        <Btn title={busy ? '처리 중…' : '참여하기'} icon="enter-outline" outline color={C.tabOn} onPress={join} />
      </Coupon>
    </Page>
  );
}
