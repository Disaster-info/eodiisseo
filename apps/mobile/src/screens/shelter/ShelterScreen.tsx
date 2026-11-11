// FS-SHELTER-001: 기준 위치 선택 → 1km 부터 1km 씩 반경 확대 검색 → 직선거리순 목록 + 지도 마커
// B안 '노선도': 내 기준 위치에서 대피소들을 역으로 잇는 녹색 노선 (노란 출구 번호 = 가까운 순위). 배경은 C안 남색 띠 + 회청 바탕
// 가장 가까운 1곳은 큰 역(경로 안내). 모든 역에서 경로를 바로 열 수 있음 (선택 단계 없음)
// FS-SHELTER-002 상세 항목(주소·유형·수용 인원·직선거리·자료 갱신일)은 별도 상세 화면 없이 역마다 바로 표시 (원본에 있는 항목만)
// 노선은 실제 길이 아니라 직선거리 순서 → '직선거리' 표기를 빼지 않음
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, Text, View, useWindowDimensions } from 'react-native';
import { TmapView } from '../../components/map';
import { BandTabs, Coupon } from '../../components/ticket';
import { Empty, Icon, Page, useTheme } from '../../components/ui';
import { MAX_RADIUS, SHELTERS, searchShelters } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const RAIL = 34; // 노선 왼쪽 칸 폭 (역 점이 놓이는 곳)

// 역 점: me = 채운 점(출발), big = 가장 가까운 대피소, end = 회색 빈 점(더 찾기)
function Dot({ kind, top = 14 }: { kind?: 'me' | 'big' | 'end'; top?: number }) {
  const { C } = useTheme();
  const size = kind === 'big' ? 24 : 18;
  return <View style={{ position: 'absolute', left: -RAIL + 12 + 3 - size / 2, top, width: size, height: size, borderRadius: size / 2, borderWidth: kind === 'big' ? 5 : 4,
    borderColor: kind === 'end' ? C.sub : C.line, backgroundColor: kind === 'me' ? C.line : C.card }} />;
}
// 노란 출구 번호 = 가까운 순위 (1번만 노랑)
function Exit({ n }: { n: number }) {
  const { C, s } = useTheme();
  return (
    <View style={{ minWidth: 26, height: 26, borderRadius: 4, paddingHorizontal: 4, justifyContent: 'center', alignItems: 'center', backgroundColor: n === 1 ? C.exit : C.border }}>
      <Text style={[s.small, s.num, { fontWeight: '900', color: n === 1 ? '#111111' : C.text }]}>{n}</Text>
    </View>
  );
}
// 경로 버튼: 모든 대피소에서 같은 모양·크기 (채운 페트롤 블루, 오른쪽 열 맨 아래)
function GoBtn({ id, name }: { id: number; name: string }) {
  const { C, s } = useTheme();
  return (
    <Pressable onPress={() => router.push(`/route/${id}`)} accessibilityRole="button" accessibilityLabel={`${name} 경로 안내`}
      style={({ pressed }) => [s.row, { gap: 4, minHeight: 40, minWidth: 84, paddingHorizontal: 12, borderRadius: 10, justifyContent: 'center', backgroundColor: C.navy }, pressed && s.pressed]}>
      <Icon name="navigate" size={16} color="#FFFFFF" />
      <Text style={[s.bold, { color: '#FFFFFF', fontSize: 15 }]}>경로</Text>
    </Pressable>
  );
}

export default function ShelterScreen() {
  const { C, s } = useTheme();
  const { location, myPos, isManual, areas, areaNames } = useApp();
  const [base, setBase] = useState(location);
  const [from, setFrom] = useState(1);
  const region = base === location || areas.includes(base) ? base : location;
  const { radius, list } = searchShelters(SHELTERS, region, from);
  const pickBase = (r: string) => { setBase(r); setFrom(1); };
  const nearest = list[0];
  // 지도 높이: 화면 높이의 30% (200~400). 크게 볼 때는 '크게 보기'(전체 화면)
  const mapHeight = Math.round(Math.min(400, Math.max(200, useWindowDimensions().height * 0.3)));

  const tabs = [
    { value: location, label: `${isManual ? '지정 위치' : '현재 위치'} · ${location}` },
    ...areas.filter(a => a !== location).map(a => ({ value: a, label: areaNames[a] ?? a })),
  ];

  return (
    <Page band={64}>
      <BandTabs label="기준 위치" items={tabs} value={region} onChange={pickBase} />

      {!nearest && (
        <Coupon><Empty icon="shield-outline" text={`최대 반경 ${MAX_RADIUS}km 안에 등록된 대피소가 없어요.\n다른 기준 위치를 선택해 보세요.`} /></Coupon>
      )}

      {nearest && <TmapView height={mapHeight} markers={[...list.map((sh, i) => ({ lat: sh.lat, lon: sh.lon, title: `${i + 1}. ${sh.name}`, label: String(i + 1) })), ...(myPos && region === location ? [{ ...myPos, title: '내 위치', me: true }] : [])]} label={`대피소 ${list.length}곳 위치 지도`} />}

      {nearest && (
        <View style={s.card}>
          <View style={[s.row, { marginBottom: 4 }]}>
            <Text style={[s.bold, { flex: 1 }]} accessibilityRole="header">대피 노선 · 반경 {radius}km</Text>
            <Text style={s.small}>직선거리순</Text>
          </View>
          <View style={{ paddingLeft: RAIL }}>
            {/* 노선 */}
            <View style={{ position: 'absolute', left: 12, top: 20, bottom: 26, width: 6, borderRadius: 3, backgroundColor: C.line }} />

            <View style={[s.row, { minHeight: 44 }]} accessible accessibilityLabel={`출발: 내 기준 위치 ${region}`}>
              <Dot kind="me" top={13} />
              <Text style={s.bold}>내 기준 위치</Text>
              <Text style={[s.small, { flex: 1 }]} numberOfLines={1}>{region}</Text>
            </View>

            {/* 역마다 같은 틀: 왼쪽 = 번호·이름·정보, 오른쪽 열 = 직선거리 + 경로 버튼. 1번(가장 가까움)만 이름·거리를 크게 */}
            {list.map((sh, i) => {
              const first = i === 0;
              return (
                <View key={sh.id} style={[s.row, { alignItems: 'flex-start', gap: 10, paddingVertical: 12 }, s.divTop]}>
                  <Dot kind={first ? 'big' : undefined} top={first ? 14 : 17} />
                  <View style={{ flex: 1, gap: 2 }} accessible
                    accessibilityLabel={`${i + 1}번${first ? ' 가장 가까운 대피소' : ''} ${sh.name}, 직선 ${sh.distance}킬로미터, ${sh.type}, ${sh.capacity ? `수용 ${sh.capacity}명` : '수용인원 정보 없음'}, ${sh.addr}`}>
                    <View style={[s.row, { alignItems: 'flex-start' }]}>
                      <Exit n={i + 1} />
                      <Text style={[first ? s.h2 : s.bold, { flex: 1, fontWeight: '900' }]} lineBreakStrategyIOS="hangul-word">{sh.name}</Text>
                    </View>
                    <Text style={first ? s.muted : s.small}>{sh.type} · {sh.capacity ? `수용 ${sh.capacity.toLocaleString()}명` : '수용인원 정보 없음'}</Text>
                    <Text style={s.small}>{sh.addr}</Text>
                    <Text style={s.small}>행정안전부 · {sh.updatedAt ? `자료 갱신 ${sh.updatedAt}` : '자료 갱신일 없음'}</Text>
                  </View>
                  <View style={{ alignItems: 'flex-end', gap: 8 }}>
                    <Text style={[s.num, first ? [s.display, { fontSize: 28, lineHeight: 32 }] : [s.bold, { fontSize: 19, fontWeight: '900' }]]}>{sh.distance}<Text style={s.small}>km</Text></Text>
                    <GoBtn id={sh.id} name={sh.name} />
                  </View>
                </View>
              );
            })}

            {radius < MAX_RADIUS ? (
              <Pressable onPress={() => setFrom(radius + 1)} accessibilityRole="button" accessibilityLabel={`반경 ${radius + 1}킬로미터로 넓혀 보기`}
                style={({ pressed }) => [s.row, { minHeight: 52 }, s.divTop, pressed && s.pressed]}>
                <Dot kind="end" top={17} />
                <Icon name="expand-outline" size={18} color={C.sub} />
                <Text style={[s.bold, { color: C.sub, flex: 1 }]}>반경 {radius + 1}km로 더 찾기</Text>
                <Text style={s.small}>최대 {MAX_RADIUS}km</Text>
              </Pressable>
            ) : (
              <View style={[s.row, { minHeight: 52 }, s.divTop]}>
                <Dot kind="end" top={17} />
                <Text style={[s.small, { flex: 1 }]}>최대 반경 {MAX_RADIUS}km까지 찾았어요</Text>
              </View>
            )}
          </View>
        </View>
      )}
      <Text style={s.small}>출처: 행정안전부 대피소 자료. 거리는 기준 위치에서 잰 직선거리예요. 노선은 실제 길이 아니에요.</Text>
    </Page>
  );
}
