// FS-SHELTER-001: 기준 위치 선택 → 1km 부터 1km 씩 반경 확대 검색 → 직선거리순 목록 + 지도 마커
// 가장 가까운 1곳이 주인공(녹색 안내판). 모든 항목에서 경로를 바로 열 수 있음 (선택 단계 없음)
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { ShelterSign } from '../../components/shelter';
import { Btn, Card, Chip, Empty, Icon, Page, useTheme } from '../../components/ui';
import { MAX_RADIUS, SHELTERS, searchShelters } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ShelterScreen() {
  const { C, s } = useTheme();
  const { location, isManual, areas, areaNames } = useApp();
  const [base, setBase] = useState(location);
  const [from, setFrom] = useState(1);
  const region = base === location || areas.includes(base) ? base : location;
  const { radius, list } = searchShelters(SHELTERS, region, from);
  const pickBase = (r: string) => { setBase(r); setFrom(1); };
  const [nearest, ...rest] = list;

  return (
    <Page>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
        <Chip text={`${isManual ? '지정 위치' : '현재 위치'} · ${location}`} on={region === location} onPress={() => pickBase(location)} />
        {areas.filter(a => a !== location).map(a => <Chip key={a} text={areaNames[a] ?? a} on={region === a} onPress={() => pickBase(a)} />)}
      </ScrollView>

      {!nearest && <Empty icon="shield-outline" text={`최대 반경 ${MAX_RADIUS}km 안에 등록된 대피소가 없어요.\n다른 기준 위치를 선택해 보세요.`} />}

      {nearest && <ShelterSign region={region} secondary={{ title: '상세정보', onPress: () => router.push(`/shelters/${nearest.id}`) }} />}

      {/* ponytail: 지도 SDK 연동 전 임시 마커. 실제 좌표 대신 순번으로 배치 */}
      {nearest && (
        <View style={[s.map, { height: 180 }]} accessible accessibilityLabel={`지도 자리 표시. 대피소 ${list.length}곳`}>
          <View style={{ position: 'absolute', left: '48%', top: '45%' }}>
            <Icon name="navigate-circle" size={28} color={C.primary} />
          </View>
          {list.map((sh, i) => (
            <View key={sh.id} style={{ position: 'absolute', left: `${12 + ((i * 29) % 70)}%`, top: `${14 + ((i * 41) % 60)}%`,
              width: 28, height: 28, borderRadius: 14, justifyContent: 'center', alignItems: 'center',
              backgroundColor: i === 0 ? C.sign.green : C.card, borderWidth: 2, borderColor: C.sign.green }}>
              <Text maxFontSizeMultiplier={1.2} style={[s.small, { fontWeight: '800', color: i === 0 ? C.onSign : C.success }]}>{i + 1}</Text>
            </View>
          ))}
          <Text style={[s.small, { position: 'absolute', bottom: 8 }]}>지도 SDK 연동 예정 · 확대/이동 지원</Text>
        </View>
      )}

      {rest.length > 0 && (
        <View style={[s.row, { marginTop: 8 }]}>
          <Text style={[s.section, { flex: 1, marginTop: 0 }]}>반경 {radius}km 내 다른 대피소</Text>
          <Text style={s.small}>직선거리순</Text>
        </View>
      )}
      {rest.map((sh, i) => (
        <Card key={sh.id} onPress={() => router.push(`/shelters/${sh.id}`)}>
          <View style={[s.row, { gap: 12 }]}>
            <View style={{ minWidth: 32, minHeight: 32, paddingHorizontal: 6, borderRadius: 999, backgroundColor: C.primarySoft, justifyContent: 'center', alignItems: 'center' }}>
              <Text style={[s.bold, s.num, { fontWeight: '800', color: C.primary }]}>{i + 2}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={s.bold}>{sh.name}</Text>
              <Text style={s.muted} numberOfLines={1}>{sh.distance}km · {sh.type}</Text>
            </View>
            <Pressable onPress={() => router.push(`/route/${sh.id}`)} accessibilityRole="button" accessibilityLabel={`${sh.name} 경로 안내`}
              style={({ pressed }) => [s.row, { gap: 4, minHeight: 44, paddingHorizontal: 12, borderRadius: 10, backgroundColor: C.fill }, pressed && s.pressed]}>
              <Icon name="navigate" size={16} color={C.onSign} />
              <Text style={[s.bold, { color: C.onSign }]}>경로</Text>
            </Pressable>
          </View>
        </Card>
      ))}
      {list.length > 0 && radius < MAX_RADIUS && (
        <Btn title={`반경 ${radius + 1}km로 넓혀 보기`} icon="expand-outline" outline onPress={() => setFrom(radius + 1)} />
      )}
    </Page>
  );
}
