// FS-AREA-001~004: 관심지역 등록·조회·변경(지역·표시명)·삭제 (최대 5개, 중복 불가)
import { useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import { Btn, C, Card, Chip, Empty, Header, Icon, Page, notify, s } from '../../components/ui';
import { REGIONS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const MAX_AREAS = 5;

export default function AreasScreen() {
  const { areas, setAreas, areaNames, setAreaNames } = useApp();
  const [editing, setEditing] = useState<string | null>(null); // 지역을 변경 중인 기존 관심지역
  const [naming, setNaming] = useState<{ area: string; text: string } | null>(null); // 표시명 수정 중
  // 표시명을 from → to 로 옮김 (to 가 null 이면 삭제)
  const moveName = (from: string, to: string | null) => setAreaNames(n => {
    const { [from]: name, ...rest } = n;
    return to && name ? { ...rest, [to]: name } : rest;
  });
  const pick = (r: string) => {
    if (areas.includes(r)) return notify('이미 등록된 관심지역입니다');
    if (editing) { setAreas(a => a.map(x => (x === editing ? r : x))); moveName(editing, r); setEditing(null); return; }
    if (areas.length >= MAX_AREAS) return notify(`관심지역은 최대 ${MAX_AREAS}개까지 등록할 수 있어요`);
    setAreas(a => [...a, r]);
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
              <Pressable onPress={() => setNaming({ area: a, text: areaNames[a] ?? '' })}><Text style={s.link}>이름</Text></Pressable>
              <Pressable onPress={() => setEditing(editing === a ? null : a)}><Text style={s.link}>{editing === a ? '취소' : '지역 변경'}</Text></Pressable>
              <Pressable onPress={() => remove(a)}><Text style={[s.link, { color: C.danger }]}>삭제</Text></Pressable>
            </View>
            {naming?.area === a && (
              <>
                <TextInput style={s.input} value={naming.text} onChangeText={text => setNaming({ area: a, text })} maxLength={10}
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
        <View style={[s.row, { flexWrap: 'wrap' }]}>
          {REGIONS.map(r => <Chip key={r} text={r} on={areas.includes(r)} onPress={() => pick(r)} />)}
        </View>
      </Page>
    </View>
  );
}
