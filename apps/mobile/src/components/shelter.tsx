// 대피 안내판 (녹색 도로표지). 홈·대피소·재난 상세가 함께 씀 — 판 하나에 결정 하나: 가장 가까운 대피소로 가는 길
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { SHELTERS, searchShelters } from '../data/mock';
import { Icon, Sign, SignBtn, useTheme } from './ui';

// region: 거리를 잰 기준 위치. secondary: 두 번째 버튼 (없으면 '대피소 더 보기')
// ponytail: 방향 화살표는 실제 좌표가 없어 생략 (방위를 지어내지 않음). 지도 SDK 로 좌표가 생기면 거리 옆에 방위 화살표 추가
export function ShelterSign({ region, secondary }: { region: string; secondary?: { title: string; onPress: () => void } }) {
  const { C, s } = useTheme();
  const { list } = searchShelters(SHELTERS, region);
  const nearest = list[0];

  if (!nearest) return (
    <Sign tone="green" onPress={() => router.navigate('/shelter')} label="대피소 찾기">
      <Text style={[s.bold, { color: C.onSign }]}>반경 5km 안에 등록된 대피소가 없어요</Text>
      <View style={s.row}>
        <Text style={[s.small, { color: C.onSign, flex: 1 }]}>대피소 탭에서 다른 기준 위치로 찾아보세요</Text>
        <Icon name="chevron-forward" size={18} color={C.onSign} />
      </View>
    </Sign>
  );

  const more = secondary ?? { title: list.length > 1 ? `다른 대피소 ${list.length - 1}곳` : '대피소 더 보기', onPress: () => router.navigate('/shelter') };
  return (
    <Sign tone="green" label={`가장 가까운 대피소 ${nearest.name}, 직선 ${nearest.distance}킬로미터`}>
      {/* 1줄: 대피소 이름 (줄 전체) / 2줄: 주소 + 큰 거리 숫자 */}
      <View style={s.row}>
        <Icon name="shield-checkmark" size={26} color={C.onSign} />
        <Text style={[s.h2, { color: C.onSign, flex: 1, fontSize: 24, lineHeight: 30, fontWeight: '800' }]} lineBreakStrategyIOS="hangul-word">{nearest.name}</Text>
      </View>
      <View style={[s.row, { alignItems: 'flex-end', gap: 12 }]}>
        <Text style={[s.small, { color: C.onSign, flex: 1 }]} lineBreakStrategyIOS="hangul-word">{nearest.type}{'\n'}{nearest.addr}</Text>
        <View style={{ alignItems: 'flex-end' }}>
          <Text style={[s.display, { color: C.onSign }]}>{nearest.distance}<Text style={[s.bold, { color: C.onSign }]}>km</Text></Text>
          <Text style={[s.small, { color: C.onSign, fontWeight: '700' }]}>{region} 기준 직선</Text>
        </View>
      </View>
      <View style={[s.row, { marginTop: 8 }]}>
        <SignBtn tone="green" solid icon="navigate" title="경로 안내" onPress={() => router.push(`/route/${nearest.id}`)} />
        <SignBtn tone="green" title={more.title} onPress={more.onPress} />
      </View>
    </Sign>
  );
}
