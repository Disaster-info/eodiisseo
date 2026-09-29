// FS-GROUP-001 생성, 002 목록, 003 초대 코드 참여
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { Badge, Btn, C, Card, Chip, Icon, Label, Page, notify, s } from '../../components/ui';
import { CATEGORIES, JOINABLE_GROUPS, ME } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const MAX_CREATE = 3;
const MAX_MEMBERS = 10;

export default function GroupScreen() {
  const { groups, setGroups } = useApp();
  const [name, setName] = useState('');
  const [category, setCategory] = useState('가족');
  const [code, setCode] = useState('');
  const created = groups.filter(g => g.createdByMe).length;

  const create = () => {
    if (!name.trim()) return notify('그룹 이름을 입력하세요');
    if (created >= MAX_CREATE) return notify(`그룹은 최대 ${MAX_CREATE}개까지 만들 수 있어요`);
    const id = Date.now();
    const newCode = `GRP-${String(id).slice(-4)}`;
    setGroups(gs => [...gs, { id, name: name.trim(), category, code: newCode, createdByMe: true,
      members: [{ id: ME, name: '나', admin: true, sharing: false, impact: '위치정보 미제공' }] }]);
    setName('');
    notify('그룹을 만들었어요', `초대 코드 ${newCode} 를 가족·친구에게 알려주세요.`);
  };

  const join = () => {
    const c = code.trim().toUpperCase();
    if (!c) return notify('초대 코드를 입력하세요');
    if (groups.some(g => g.code === c)) return notify('이미 참여 중인 그룹입니다');
    const found = JOINABLE_GROUPS.find(g => g.code === c); // 실제로는 서버 조회
    if (!found) return notify('참여 실패', '유효하지 않은 초대 코드입니다.');
    if (found.members.length >= MAX_MEMBERS) return notify('참여 실패', `그룹 인원이 가득 찼어요 (최대 ${MAX_MEMBERS}명)`);
    setGroups(gs => [...gs, { ...found, members: [...found.members, { id: ME, name: '나', admin: false, sharing: false, impact: '위치정보 미제공' }] }]);
    setCode('');
    notify('참여 완료', `${found.name} 그룹에 참여했어요`);
  };

  return (
    <Page>
      <Text style={s.section}>참여 중인 그룹</Text>
      {groups.length === 0 && <Text style={s.muted}>참여 중인 그룹이 없어요. 그룹을 만들거나 초대 코드로 참여하세요.</Text>}
      {groups.map(g => (
        <Card key={g.id} onPress={() => router.push(`/groups/${g.id}`)}>
          <View style={[s.row, { gap: 12 }]}>
            <View style={{ width: 44, height: 44, borderRadius: 14, backgroundColor: C.primarySoft, justifyContent: 'center', alignItems: 'center' }}>
              <Icon name="people" size={22} color={C.primary} />
            </View>
            <View style={{ flex: 1 }}>
              <View style={s.row}>
                <Text style={s.bold}>{g.name}</Text>
                <Badge text={g.category} color={C.sub} />
                {g.members.find(m => m.id === ME)?.admin && <Badge text="관리자" color={C.primary} />}
              </View>
              <Text style={s.muted}>구성원 {g.members.length}/{MAX_MEMBERS} · 영향지역 {g.members.filter(m => m.impact === '영향지역 포함').length}명</Text>
            </View>
            <Icon name="chevron-forward" size={18} color={C.sub} />
          </View>
        </Card>
      ))}
      <Card>
        <Label icon="add-circle" text={`새 그룹 만들기 (${created}/${MAX_CREATE})`} />
        <TextInput style={s.input} placeholder="그룹 이름" value={name} onChangeText={setName} maxLength={20} />
        <View style={[s.row, { flexWrap: 'wrap' }]}>{CATEGORIES.map(c => <Chip key={c} text={c} on={category === c} onPress={() => setCategory(c)} />)}</View>
        <Btn title="만들기" icon="add" onPress={create} />
      </Card>
      <Card>
        <Label icon="key" text="초대 코드로 참여" />
        <TextInput style={s.input} placeholder="예: FRD-1234" value={code} onChangeText={setCode} autoCapitalize="characters" autoCorrect={false} />
        <Btn title="참여하기" icon="enter-outline" outline onPress={join} />
      </Card>
    </Page>
  );
}
