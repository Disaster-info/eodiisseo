// FS-GROUP-004 그룹원 관리, 005 탈퇴·권한 이전, 006 위치공유, 007 위치 조회, 008 재난 영향상태, 009 그룹 정보 수정
import * as Clipboard from 'expo-clipboard';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, Switch, Text, TextInput, View } from 'react-native';
import { Btn, C, Card, Chip, Header, Icon, Label, MapBox, Page, STALE_MIN, confirm, freshness, notify, s } from '../../components/ui';
import { CATEGORIES, IMPACT_STYLE, ME, Member } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function GroupDetailScreen({ id }: { id: number }) {
  const { groups, setGroups, locConsent, location } = useApp();
  const [edit, setEdit] = useState<{ name: string; category: string } | null>(null); // 그룹 정보 수정 중
  const [picking, setPicking] = useState(false); // 관리자 탈퇴: 권한 넘길 사람 고르는 중
  const [copied, setCopied] = useState(false);   // 초대 코드 복사 직후 2초간 '복사됨' 표시
  const g = groups.find(x => x.id === id);
  const me = g?.members.find(m => m.id === ME);
  if (!g || !me) return <View style={s.fill}><Header title="안심 그룹" /><Text style={s.center}>그룹을 찾을 수 없습니다.</Text></View>;

  const update = (fn: (ms: Member[]) => Member[]) =>
    setGroups(gs => gs.map(x => (x.id === id ? { ...x, members: fn(x.members) } : x)));

  const toggleShare = (on: boolean) => {
    if (on && !locConsent) return notify('위치정보 이용 동의가 필요합니다', '내정보 › 위치 설정에서 동의해 주세요.');
    update(ms => ms.map(m => m.id !== ME ? m : on
      ? { ...m, sharing: true, place: location, updatedMin: 0, impact: '판단 불가' }
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
        <Card>
          <View style={s.row}>
            <Icon name="key-outline" size={18} color={C.sub} />
            <Text style={[s.body, { flex: 1 }]}>초대 코드 <Text style={[s.bold, { color: C.primary, letterSpacing: 1 }]} selectable>{g.code}</Text></Text>
            <Pressable onPress={copyCode} hitSlop={8} accessibilityRole="button" accessibilityLabel="초대 코드 복사"
              style={({ pressed }) => [s.row, { gap: 4, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 999,
                backgroundColor: copied ? C.success + '1A' : C.primarySoft }, pressed && s.pressed]}>
              <Icon name={copied ? 'checkmark' : 'copy-outline'} size={16} color={copied ? C.success : C.primary} />
              <Text style={{ fontSize: 13, fontWeight: '700', color: copied ? C.success : C.primary }}>{copied ? '복사됨' : '복사'}</Text>
            </Pressable>
          </View>
          <Text style={s.muted}>{g.category} · 구성원 {g.members.length}/10</Text>
          {me.admin && !edit && (
            <Pressable onPress={() => setEdit({ name: g.name, category: g.category })}><Text style={[s.link, { marginLeft: 0, marginTop: 8 }]}>그룹 정보 수정</Text></Pressable>
          )}
        </Card>
        {edit && (
          <Card>
            <Label icon="create-outline" text="그룹 정보 수정" />
            <TextInput style={s.input} value={edit.name} onChangeText={name => setEdit({ ...edit, name })} maxLength={20} placeholder="그룹 이름" placeholderTextColor={C.sub} />
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
            <Switch value={me.sharing} onValueChange={toggleShare} trackColor={{ true: C.primary }} />
          </View>
          <Text style={s.muted}>OFF 시 즉시 그룹원에게 위치 제공이 중단됩니다.</Text>
        </Card>
        <MapBox><Text style={s.muted}>그룹원 위치 지도 (공유 ON 구성원만 표시)</Text></MapBox>
        <Text style={s.section}>구성원</Text>
        {g.members.map(m => {
          const st = IMPACT_STYLE[m.impact];
          // 오래된 위치로는 영향상태를 확정하지 않음
          const old = (m.updatedMin ?? 0) > STALE_MIN && (m.impact === '영향지역 포함' || m.impact === '영향지역 밖');
          return (
            <Card key={m.id}>
              <View style={s.row}>
                <Icon name="person-circle" size={36} color={C.sub} />
                <View style={{ flex: 1 }}>
                  <View style={s.row}>
                    <Text style={s.bold}>{m.name}</Text>
                    {m.admin && <Icon name="ribbon" size={16} color={C.warning} accessibilityLabel="관리자" />}
                  </View>
                  <Text style={s.muted}>{m.sharing ? `${m.place} · ${freshness(m.updatedMin)}` : '위치공유 OFF'}</Text>
                </View>
                <View style={{ alignItems: 'flex-end' }}>
                  <View style={s.row}>
                    <Icon name={st.icon} size={16} color={old ? C.sub : st.color} />
                    <Text style={{ color: old ? C.sub : st.color, fontSize: 13, fontWeight: '600' }}>{m.impact}</Text>
                  </View>
                  {old && <Text style={[s.muted, { fontSize: 11 }]}>오래된 위치 기준 · 확인 필요</Text>}
                </View>
              </View>
              {me.admin && m.id !== ME && (
                <View style={[s.row, { marginTop: 8 }]}>
                  <Pressable onPress={() => transfer(m)}><Text style={s.link}>관리자 이전</Text></Pressable>
                  <Pressable onPress={() => kick(m)}><Text style={[s.link, { color: C.danger }]}>제외</Text></Pressable>
                </View>
              )}
            </Card>
          );
        })}
        {picking ? (
          <Card>
            <Label icon="ribbon" text="관리자 권한을 넘길 구성원 선택" color={C.warning} />
            <Text style={s.muted}>관리자는 권한을 넘긴 뒤에만 탈퇴할 수 있어요.</Text>
            {g.members.filter(m => m.id !== ME).map(m => (
              <Pressable key={m.id} onPress={() => transferAndLeave(m)} style={({ pressed }) => [s.row, { paddingVertical: 10 }, pressed && s.pressed]}>
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
