// FS-GROUP-004 그룹원 관리, 005 탈퇴·권한 이전, 006 위치공유, 007 위치 조회, 008 재난 영향상태, 009 그룹 정보 수정
import * as Clipboard from 'expo-clipboard';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { AppSwitch, ActionMenu, Btn, Card, Chip, Header, Icon, Label, MapBox, Page, STALE_MIN, confirm, freshness, impactColor, knownImpact, notify, useTheme } from '../../components/ui';
import { CATEGORIES, GPS_LOCATION, IMPACT_ICON, Impact, ME, Member } from '../../data/mock';
import { useApp } from '../../store/AppContext';

// 영향지역 포함 → 판단 불가 → 위치 미제공 → 밖 (확인이 필요한 사람부터)
const IMPACT_ORDER: Record<Impact, number> = { '영향지역 포함': 0, '판단 불가': 1, '위치정보 미제공': 2, '영향지역 밖': 3 };

export default function GroupDetailScreen({ id }: { id: number }) {
  const { C, s } = useTheme();
  const { groups, setGroups, locConsent } = useApp();
  const [edit, setEdit] = useState<{ name: string; category: string } | null>(null); // 그룹 정보 수정 중
  const [picking, setPicking] = useState(false); // 관리자 탈퇴: 권한 넘길 사람 고르는 중
  const [copied, setCopied] = useState(false);   // 초대 코드 복사 직후 2초간 '복사됨' 표시
  const g = groups.find(x => x.id === id);
  const me = g?.members.find(m => m.id === ME);
  if (!g || !me) return <View style={s.fill}><Header title="안심 그룹" /><Text style={s.center}>그룹을 찾을 수 없습니다.</Text></View>;

  const update = (fn: (ms: Member[]) => Member[]) =>
    setGroups(gs => gs.map(x => (x.id === id ? { ...x, members: fn(x.members) } : x)));

  // 공유 위치는 항상 기기 GPS 위치. 직접 지정한 기준 위치는 공유하지 않음
  const toggleShare = (on: boolean) => {
    if (on && !locConsent) return notify('위치정보 이용 동의가 필요합니다', '내정보 › 위치 설정에서 동의해 주세요.');
    update(ms => ms.map(m => m.id !== ME ? m : on
      ? { ...m, sharing: true, place: GPS_LOCATION, updatedMin: 0, impact: '판단 불가' }
      : { ...m, sharing: false, place: undefined, updatedMin: undefined, impact: '위치정보 미제공' }));
  };
  const kick = (m: Member) => confirm('구성원 제외', `${m.name}님을 그룹에서 제외할까요?`, '제외',
    () => update(ms => ms.filter(x => x.id !== m.id)));
  const transfer = (m: Member) => confirm('관리자 권한 이전', `${m.name}님에게 관리자 권한을 넘길까요?`, '이전',
    () => update(ms => ms.map(x => ({ ...x, admin: x.id === m.id }))));
  // 실제로는 서버가 권한 이전 + 탈퇴를 한 처리 단위로 반영. 목업은 내 목록에서만 제거
  const transferAndLeave = (m: Member) => confirm('권한 이전 후 탈퇴', `${m.name}님에게 관리자 권한을 넘기고 탈퇴할까요?`, '탈퇴', () => {
    router.back();
    setGroups(gs => gs.filter(x => x.id !== id)); // 목록에서 제거 = 내 그룹 접근·위치공유 즉시 해제
  });
  const leave = () => {
    if (me.admin && g.members.length > 1) return setPicking(true);
    // ponytail: 마지막 1인 관리자 탈퇴 시 그룹 처리 정책은 명세상 미정 → 임시로 목록에서 제거
    confirm('그룹 탈퇴', me.admin ? `마지막 구성원이에요. ${g.name}에서 탈퇴할까요?` : `${g.name}에서 탈퇴할까요?`, '탈퇴', () => {
      router.back();
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

  return (
    <View style={s.fill}>
      <Header title={g.name} />
      <Page>
        <Text style={[s.section, { marginTop: 0 }]}>구성원 · 위험한 순</Text>
        <View style={[s.card, { paddingVertical: 4 }]}>
        {[...g.members].sort((a, b) => IMPACT_ORDER[knownImpact(a)] - IMPACT_ORDER[knownImpact(b)]).map((m, n) => {
          const color = impactColor(C, m.impact);
          // 오래된 위치로는 영향상태를 확정하지 않음
          const old = (m.updatedMin ?? 0) > STALE_MIN && (m.impact === '영향지역 포함' || m.impact === '영향지역 밖');
          return (
            <View key={m.id} style={[{ paddingVertical: 12 }, n > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.border }]}>
              <View style={s.row}>
                <Icon name="person-circle" size={36} color={C.sub} />
                <View style={{ flex: 1 }}>
                  <View style={s.row}>
                    <Text style={s.bold}>{m.name}</Text>
                    {m.admin && <Icon name="ribbon" size={16} color={C.warning} accessibilityLabel="관리자" />}
                  </View>
                  {/* 영향상태는 이름 아래 한 줄 (오른쪽 칸으로 두면 '…' 버튼과 함께 이름 폭을 너무 줄임) */}
                  <View style={[s.row, { gap: 4, marginTop: 2 }]}>
                    <Icon name={IMPACT_ICON[knownImpact(m)]} size={16} color={old ? C.sub : color} />
                    <Text style={[s.muted, { marginTop: 0, fontWeight: '700', color: old ? C.sub : color, flexShrink: 1 }]}>{m.impact}{old ? ' · 오래된 위치 기준, 확인 필요' : ''}</Text>
                  </View>
                  <Text style={s.muted}>{m.sharing ? `${m.place} · ${freshness(m.updatedMin)}` : '위치공유 OFF'}</Text>
                </View>
                {/* 관리자 동작은 '…' 메뉴로 (제외는 확인 후 처리) */}
                {me.admin && m.id !== ME && (
                  <ActionMenu title={m.name} items={[
                    { text: '관리자 권한 넘기기', onPress: () => transfer(m) },
                    { text: '그룹에서 제외', destructive: true, onPress: () => kick(m) },
                  ]} />
                )}
              </View>
            </View>
          );
        })}
        </View>
        <Card>
          <View style={s.row}>
            <Icon name="key-outline" size={18} color={C.sub} />
            <Text style={[s.body, { flex: 1 }]}>초대 코드 <Text style={[s.bold, { color: C.primary, letterSpacing: 1 }]} selectable>{g.code}</Text></Text>
            <Pressable onPress={copyCode} hitSlop={8} accessibilityRole="button" accessibilityLabel="초대 코드 복사"
              style={({ pressed }) => [s.row, { gap: 4, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 999,
                backgroundColor: copied ? C.success + '1A' : C.primarySoft }, pressed && s.pressed]}>
              <Icon name={copied ? 'checkmark' : 'copy-outline'} size={16} color={copied ? C.success : C.primary} />
              <Text style={[s.small, { fontWeight: '700', color: copied ? C.success : C.primary }]}>{copied ? '복사됨' : '복사'}</Text>
            </Pressable>
          </View>
          <Text style={s.muted}>{g.category} · 구성원 {g.members.length}/10</Text>
          {me.admin && !edit && (
            <Pressable onPress={() => setEdit({ name: g.name, category: g.category })} accessibilityRole="button"><Text style={[s.link, { marginLeft: 0, marginTop: 8 }]}>그룹 정보 수정</Text></Pressable>
          )}
        </Card>
        {me.admin && edit && (
          <Card>
            <Label icon="create-outline" text="그룹 정보 수정" />
            <TextInput accessibilityLabel="그룹 이름" style={s.input} value={edit.name} onChangeText={name => setEdit({ ...edit, name })} maxLength={20} placeholder="그룹 이름" placeholderTextColor={C.sub} />
            <View style={[s.row, { flexWrap: 'wrap' }]}>{CATEGORIES.map(c => <Chip key={c} text={c} on={edit.category === c} onPress={() => setEdit({ ...edit, category: c })} />)}</View>
            <View style={s.row}>
              <View style={{ flex: 1 }}><Btn title="취소" outline onPress={() => setEdit(null)} /></View>
              <View style={{ flex: 1 }}><Btn title="저장" onPress={saveEdit} /></View>
            </View>
          </Card>
        )}
        <Card>
          <View style={s.row}>
            <Icon name="navigate-circle-outline" size={22} color={C.primary} />
            <Text style={[s.bold, { flex: 1 }]}>내 위치 공유</Text>
            <AppSwitch accessibilityLabel="내 위치 공유" value={me.sharing} onValueChange={toggleShare} />
          </View>
          <Text style={s.muted}>OFF 시 즉시 그룹원에게 위치 제공이 중단됩니다.</Text>
        </Card>
        <MapBox><Text style={s.muted}>그룹원 위치 지도 (공유 ON 구성원만 표시)</Text></MapBox>
        {picking ? (
          <Card>
            <Label icon="ribbon" text="관리자 권한을 넘길 구성원 선택" color={C.warning} />
            <Text style={s.muted}>관리자는 권한을 넘긴 뒤에만 탈퇴할 수 있어요.</Text>
            {g.members.filter(m => m.id !== ME).map(m => (
              <Pressable key={m.id} onPress={() => transferAndLeave(m)} accessibilityRole="button" accessibilityLabel={`${m.name}님에게 넘기고 탈퇴`} style={({ pressed }) => [s.row, { paddingVertical: 10 }, pressed && s.pressed]}>
                <Icon name="person-circle-outline" size={24} color={C.sub} />
                <Text style={[s.body, { flex: 1 }]}>{m.name}</Text>
                <Icon name="chevron-forward" size={18} color={C.sub} />
              </Pressable>
            ))}
            <Btn title="취소" outline onPress={() => setPicking(false)} />
          </Card>
        ) : (
          <Btn title="그룹 탈퇴" icon="exit-outline" color={C.danger} outline onPress={leave} />
        )}
      </Page>
    </View>
  );
}
