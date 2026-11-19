// 대피 안내 (C안 '탑승권'의 대피 칸): 출발(기준 위치) → 도착(가장 가까운 대피소) + 직선거리 + 경로 안내
// 재난 상세가 씀 — 판 하나에 결정 하나: 가장 가까운 대피소로 가는 길
import { router } from 'expo-router';
import { ActivityIndicator, Text, View } from 'react-native';
import { useShelters } from '../data/server';
import { MAX_RADIUS } from '../data/types';
import { useApp } from '../store/AppContext';
import { Coupon, Field, Perf } from './ticket';
import { Btn, Icon, useTheme } from './ui';

// region: 거리를 잰 기준 위치. secondary: 두 번째 버튼 (없으면 '대피소 더 보기')
// ponytail: 방향 화살표는 실제 좌표가 없어 생략 (방위를 지어내지 않음). 지도 SDK 로 좌표가 생기면 거리 옆에 방위 화살표 추가
// unknown: 기준 위치를 아직 모름(GPS 확인 중·실패) → '대피소 없음'으로 단정하지 않고 위치 지정 안내
export function ShelterSign({ region, secondary, unknown }: { region: string; secondary?: { title: string; onPress: () => void }; unknown?: boolean }) {
  const { C, s } = useTheme();
  const { basePos } = useApp();
  const found = useShelters(unknown ? null : { pos: basePos, region });
  const list = found.data?.list ?? [];
  const nearest = list[0];

  if (unknown) return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark">
      <Text style={s.bold}>현재 위치를 확인하지 못해 가까운 대피소를 찾을 수 없어요</Text>
      <Text style={s.muted}>위치 권한을 확인하거나 기준 위치를 직접 지정하세요.</Text>
      <Btn title="기준 위치 지정" icon="pin-outline" outline color={C.tabOn} onPress={() => router.push('/location')} />
    </Coupon>
  );

  // 찾는 중·실패는 '대피소 없음'과 다르게 (없다고 단정하지 않음)
  if (found.loading) return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark">
      <View style={[s.row, { minHeight: 48 }]}><ActivityIndicator color={C.tabOn} /><Text style={s.muted}>가까운 대피소를 찾는 중…</Text></View>
    </Coupon>
  );
  if (found.error) return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark">
      <Text style={s.bold}>대피소 정보를 불러오지 못했어요</Text>
      <Text style={s.muted}>{found.error}</Text>
      <Btn title="다시 시도" icon="refresh" outline color={C.tabOn} onPress={found.reload} />
    </Coupon>
  );

  if (!nearest) return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark">
      <Text style={s.bold}>반경 {MAX_RADIUS}km 안에 등록된 대피소가 없어요</Text>
      <Text style={s.muted}>대피소 탭에서 다른 기준 위치로 찾아보세요.</Text>
      <Btn title="대피소 찾기" icon="shield-outline" outline color={C.tabOn} onPress={() => router.navigate('/shelter')} />
    </Coupon>
  );

  const more = secondary ?? { title: list.length > 1 ? `다른 대피소 ${list.length - 1}곳` : '대피소 더 보기', onPress: () => router.navigate('/shelter') };
  return (
    <Coupon tone="green" head="대피 안내" headIcon="shield-checkmark" right="가장 가까운 대피소">
      <View style={[s.row, { gap: 10, alignItems: 'flex-start' }]} accessible accessibilityLabel={`출발 ${region}, 도착 ${nearest.name}${nearest.type ? `, ${nearest.type}` : ''}`}>
        <View style={{ flex: 1 }}>
          <Text style={s.small}>출발</Text>
          <Text style={s.bold}>{region}</Text>
        </View>
        <Icon name="arrow-forward" size={22} color={C.sub} style={{ marginTop: 14 }} />
        <View style={{ flex: 1.4 }}>
          <Text style={s.small}>도착{nearest.type ? ` · ${nearest.type}` : ''}</Text>
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
