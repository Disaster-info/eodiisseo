// FS-SHELTER-001: 기준 위치 선택 → 1km 부터 1km 씩 반경 확대 검색 → 직선거리순 목록 + 지도 마커 연동
import { router } from 'expo-router';
import { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { Btn, C, Card, Chip, Empty, Icon, Page, s } from '../../components/ui';
import { MAX_RADIUS, SHELTERS, searchShelters } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ShelterScreen() {
  const { location, isManual, areas, areaNames } = useApp();
  const [base, setBase] = useState(location);
  const [from, setFrom] = useState(1);
  const [sel, setSel] = useState<number | null>(null);
  const region = base === location || areas.includes(base) ? base : location;
  const { radius, list } = searchShelters(SHELTERS, region, from);
  const pickBase = (r: string) => { setBase(r); setFrom(1); setSel(null); };

  return (
    <Page>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
        <Chip text={`${isManual ? '지정 위치' : '현재 위치'} · ${location}`} on={region === location} onPress={() => pickBase(location)} />
        {areas.filter(a => a !== location).map(a => <Chip key={a} text={areaNames[a] ?? a} on={region === a} onPress={() => pickBase(a)} />)}
      </ScrollView>

      {/* ponytail: 지도 SDK 연동 전 임시 마커. 실제 좌표 대신 순번으로 배치 */}
      <View style={[s.map, { height: 200 }]}>
        <View style={{ position: 'absolute', left: '48%', top: '45%', alignItems: 'center' }}>
          <Icon name="navigate-circle" size={28} color={C.primary} />
        </View>
        {list.map((sh, i) => (
          <View key={sh.id} style={{ position: 'absolute', left: `${12 + ((i * 29) % 70)}%`, top: `${14 + ((i * 41) % 60)}%` }}>
            <View style={{ width: 28, height: 28, borderRadius: 14, justifyContent: 'center', alignItems: 'center',
              backgroundColor: sel === sh.id ? C.danger : C.card, borderWidth: 2, borderColor: sel === sh.id ? C.danger : C.primary }}>
              <Text style={{ fontSize: 12, fontWeight: '800', color: sel === sh.id ? '#fff' : C.primary }}>{i + 1}</Text>
            </View>
          </View>
        ))}
        <Text style={[s.muted, { position: 'absolute', bottom: 8 }]}>지도 SDK 연동 예정 · 확대/이동 지원</Text>
      </View>

      <View style={s.row}>
        <Text style={[s.section, { flex: 1 }]}>반경 {radius}km 내 대피소</Text>
        <Text style={[s.muted, { marginTop: 8 }]}>직선거리순</Text>
      </View>
      {list.length === 0 && <Empty icon="shield-outline" text={`최대 반경 ${MAX_RADIUS}km 안에 등록된 대피소가 없어요.\n다른 기준 위치를 선택해 보세요.`} />}
      {list.map((sh, i) => (
        <Card key={sh.id} onPress={() => setSel(sel === sh.id ? null : sh.id)}>
          <View style={[s.row, { gap: 12 }]}>
            <View style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: sel === sh.id ? C.danger : C.primarySoft, justifyContent: 'center', alignItems: 'center' }}>
              <Text style={{ fontWeight: '800', color: sel === sh.id ? '#fff' : C.primary }}>{i + 1}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={s.bold}>{sh.name}</Text>
              <Text style={s.muted} numberOfLines={1}>{sh.type} · {sh.addr}</Text>
            </View>
            <Text style={[s.bold, { color: C.primary }]}>{sh.distance}km</Text>
          </View>
          {sel === sh.id && (
            <View style={[s.row, { marginTop: 12 }]}>
              <View style={{ flex: 1 }}><Btn title="상세정보" outline onPress={() => router.push(`/shelters/${sh.id}`)} /></View>
              <View style={{ flex: 1 }}><Btn title="경로 보기" icon="navigate" onPress={() => router.push(`/route/${sh.id}`)} /></View>
            </View>
          )}
        </Card>
      ))}
      {list.length > 0 && radius < MAX_RADIUS && (
        <Btn title={`반경 ${radius + 1}km로 넓혀 보기`} icon="expand-outline" outline onPress={() => setFrom(radius + 1)} />
      )}
    </Page>
  );
}
