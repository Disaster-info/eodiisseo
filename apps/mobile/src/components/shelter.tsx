// 대피 안내 (C안 '탑승권'의 대피 칸): 출발(기준 위치) → 도착(가장 가까운 대피소) + 직선거리 + 경로 안내
// 재난 상세가 씀 — 판 하나에 결정 하나: 가장 가까운 대피소로 가는 길
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { SHELTERS, searchShelters } from '../data/mock';
import { Coupon, Field, Perf } from './ticket';
import { Btn, Icon, useTheme } from './ui';

// region: 거리를 잰 기준 위치. secondary: 두 번째 버튼 (없으면 '대피소 더 보기')
// ponytail: 방향 화살표는 실제 좌표가 없어 생략 (방위를 지어내지 않음). 지도 SDK 로 좌표가 생기면 거리 옆에 방위 화살표 추가
export function ShelterSign({ region, secondary }: { region: string; secondary?: { title: string; onPress: () => void } }) {
  const { C, s } = useTheme();
  const { list } = searchShelters(SHELTERS, region);
  const nearest = list[0];

  if (!nearest) return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark">
      <Text style={s.bold}>반경 5km 안에 등록된 대피소가 없어요</Text>
      <Text style={s.muted}>대피소 탭에서 다른 기준 위치로 찾아보세요.</Text>
      <Btn title="대피소 찾기" icon="shield-outline" outline color={C.tabOn} onPress={() => router.navigate('/shelter')} />
    </Coupon>
  );

  const more = secondary ?? { title: list.length > 1 ? `다른 대피소 ${list.length - 1}곳` : '대피소 더 보기', onPress: () => router.navigate('/shelter') };
  return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark" right="가장 가까운 대피소">
      <View style={[s.row, { gap: 10, alignItems: 'flex-start' }]} accessible accessibilityLabel={`출발 ${region}, 도착 ${nearest.name}, ${nearest.type}`}>
        <View style={{ flex: 1 }}>
          <Text style={s.small}>출발</Text>
          <Text style={s.bold}>{region}</Text>
        </View>
        <Icon name="arrow-forward" size={22} color={C.sub} style={{ marginTop: 14 }} />
        <View style={{ flex: 1.4 }}>
          <Text style={s.small}>도착 · {nearest.type}</Text>
          <Text style={[s.bold, { fontWeight: '900' }]} lineBreakStrategyIOS="hangul-word">{nearest.name}</Text>
        </View>
      </View>
      <Perf />
      <View style={[s.row, { gap: 12 }]}>
        <Field label="직선거리" value={`${nearest.distance}km`} mono />
        <Field label="수용" value={nearest.capacity ? `${nearest.capacity.toLocaleString()}명` : '정보 없음'} />
      </View>
      <Text style={s.small}>{nearest.addr}</Text>
      <View style={[s.row, { marginTop: 10 }]}>
        <View style={{ flex: 1.3 }}><Btn title="경로 안내" icon="navigate" color={C.navy} onPress={() => router.push(`/route/${nearest.id}`)} /></View>
        <View style={{ flex: 1 }}><Btn title={more.title} outline color={C.tabOn} onPress={more.onPress} /></View>
      </View>
    </Coupon>
  );
}
