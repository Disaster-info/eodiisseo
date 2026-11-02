// FS-AREA-001~004: 관심지역 등록·조회·변경(지역·표시명)·삭제 (최대 5개, 중복 불가)
import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { Btn, Card, Chip, Empty, Header, Icon, Page, notify, useTheme, ActionMenu, Badge, confirm } from '../../components/ui';
import { REGIONS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const MAX_AREAS = 5;

export default function AreasScreen() {
  const { C, s } = useTheme();
  const { areas, setAreas, areaNames, setAreaNames, setNoti } = useApp();
  const [editing, setEditing] = useState<string | null>(null); // 지역을 변경 중인 기존 관심지역
  const [naming, setNaming] = useState<{ area: string; text: string } | null>(null); // 표시명 수정 중
  // 표시명을 from → to 로 옮김 (to 가 null 이면 삭제)
  const moveName = (from: string, to: string | null) => setAreaNames(n => {
    const { [from]: name, ...rest } = n;
    return to && name ? { ...rest, [to]: name } : rest;
  });
  const pick = (r: string) => {
    if (areas.includes(r)) return notify('이미 등록된 관심지역입니다');
    // 알림 대상도 함께 바꿈 (지역을 바꾸거나 새로 추가했는데 알림이 조용히 빠지지 않도록)
    if (editing) {
      setAreas(a => a.map(x => (x === editing ? r : x))); moveName(editing, r);
      setNoti(n => ({ ...n, areas: n.areas.includes(editing) ? [...n.areas.filter(x => x !== editing && x !== r), r] : n.areas }));
      setEditing(null); return;
    }
    if (areas.length >= MAX_AREAS) return notify(`관심지역은 최대 ${MAX_AREAS}개까지 등록할 수 있어요`);
    setAreas(a => [...a, r]);
    setNoti(n => ({ ...n, areas: [...n.areas.filter(x => x !== r), r] }));
  };
  const remove = (a: string) => {
    if (editing === a) setEditing(null);
    setAreas(x => x.filter(y => y !== a));
    moveName(a, null);
  };
  const saveName = () => {
    if (!naming) return;
    const text = naming.text.trim();
    setAreaNames(n => { const { [naming.area]: _, ...rest } = n; return text ? { ...rest, [naming.area]: text } : rest; });
    setNaming(null);
  };
  return (
    <View style={s.fill}>
      <Header title={`관심지역 (${areas.length}/${MAX_AREAS})`} />
      <Page>
        {areas.length === 0 && <Empty icon="star-outline" text={'등록된 관심지역이 없어요.\n아래에서 지역을 추가하세요. 등록하지 않아도 앱은 그대로 쓸 수 있어요.'} />}
        {areas.map(a => (
          <Card key={a}>
            <View style={s.row}>
              <Icon name="star" size={18} color={C.warning} />
              <View style={{ flex: 1 }}>
                <Text style={s.bold}>{areaNames[a] ?? a}</Text>
                {!!areaNames[a] && <Text style={[s.muted, { marginTop: 0 }]}>{a}</Text>}
              </View>
              {editing === a && <Badge text="변경 중" color={C.primary} />}
              <ActionMenu title={areaNames[a] ?? a} items={[
                { text: '표시 이름 바꾸기', onPress: () => setNaming({ area: a, text: areaNames[a] ?? '' }) },
                { text: '다른 지역으로 변경', onPress: () => setEditing(a) },
                { text: '관심지역 삭제', destructive: true, onPress: () => confirm('관심지역 삭제', `${areaNames[a] ?? a}을(를) 관심지역에서 삭제할까요?\n이 지역 알림도 함께 꺼져요.`, '삭제', () => remove(a)) },
              ]} />
            </View>
            {naming?.area === a && (
              <>
                <TextInput accessibilityLabel="관심지역 표시 이름" style={s.input} value={naming.text} onChangeText={text => setNaming({ area: a, text })} maxLength={10}
                  placeholder="예: 우리집, 회사 (비우면 지역명)" placeholderTextColor={C.sub} autoFocus onSubmitEditing={saveName} />
                <View style={s.row}>
                  <View style={{ flex: 1 }}><Btn title="취소" outline onPress={() => setNaming(null)} /></View>
                  <View style={{ flex: 1 }}><Btn title="저장" onPress={saveName} /></View>
                </View>
              </>
            )}
          </Card>
        ))}
        <Text style={s.section}>{editing ? `'${editing}' 을(를) 변경할 지역 선택` : '지역 추가'}</Text>
        {editing && <Btn title="지역 변경 취소" outline onPress={() => setEditing(null)} />}
        <View style={[s.row, { flexWrap: 'wrap' }]}>
          {REGIONS.map(r => <Chip key={r} text={r} on={areas.includes(r)} onPress={() => pick(r)} />)}
        </View>
      </Page>
    </View>
  );
}
