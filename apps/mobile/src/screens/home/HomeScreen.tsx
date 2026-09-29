// 대시보드: FS-DASH-001 주요 재난·바로가기, FS-WEATHER-001 날씨, 관심지역 재난 우선 표시
import { Href, router } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { DisasterCard } from '../../components/disaster';
import { C, Card, Chip, Empty, Icon, IconName, Page, s } from '../../components/ui';
import { DISASTERS, WEATHER, WEATHER_ICON, WEATHER_TIP, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const CURRENT = '현재 위치';
// navigate: 탭이면 탭 전환, 아니면 새 화면을 쌓음
const SHORTCUTS: { icon: IconName; label: string; to: Href }[] = [
  { icon: 'shield-checkmark', label: '대피소', to: '/shelter' },
  { icon: 'chatbubbles', label: '행동요령', to: '/chat' },
  { icon: 'people', label: '안심그룹', to: '/group' },
  { icon: 'notifications', label: '알림', to: '/noti' },
];

export default function HomeScreen() {
  const { location, locConsent, areas, areaNames } = useApp();
  const [picked, setPicked] = useState(CURRENT);
  const base = areas.includes(picked) ? picked : CURRENT; // 선택했던 관심지역이 삭제되면 현재 위치로
  const region = base === CURRENT ? location : base;
  const w = WEATHER[region]; // 없으면 조회 실패 — 임의 값을 만들지 않음
  const tip = w && WEATHER_TIP[w.cond];
  // 종료·해제 제외, 현재 위치·관심지역 관련 재난 우선
  const near = (r: string) => Number(r === location || areas.includes(r));
  const active = DISASTERS.filter(isActive).sort((a, b) => near(b.region) - near(a.region));

  return (
    <Page>
      <View style={s.row}>
        <Icon name={locConsent ? 'navigate' : 'pin'} size={14} color={C.sub} />
        <Text style={[s.muted, { marginTop: 0 }]}>{locConsent ? '현재 위치' : '직접 지정'} · {location}</Text>
      </View>

      <Card>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
          {[CURRENT, ...areas].map(a => <Chip key={a} text={areaNames[a] ?? a} on={base === a} onPress={() => setPicked(a)} />)}
          {areas.length === 0 && <Chip text="+ 관심지역" on={false} onPress={() => router.push('/areas')} />}
        </ScrollView>
        {w ? (
          <>
            <View style={[s.row, { marginTop: 12, gap: 16 }]}>
              <View style={{ width: 64, height: 64, borderRadius: 20, backgroundColor: C.primarySoft, justifyContent: 'center', alignItems: 'center' }}>
                <Icon name={WEATHER_ICON[w.cond]} size={36} color={C.primary} />
              </View>
              <View>
                <Text style={[s.h1, { fontSize: 34 }]}>{w.temp}°</Text>
                <Text style={s.body}>{w.cond}</Text>
              </View>
            </View>
            {!!tip && <Text style={[s.body, { marginTop: 10 }]}>{tip}</Text>}
            <Text style={s.muted}>{region} · {w.at} 기준</Text>
          </>
        ) : (
          <View style={[s.row, { marginTop: 12 }]}>
            <Icon name="cloud-offline-outline" size={22} color={C.sub} />
            <Text style={s.muted}>{region}의 날씨 정보를 확인할 수 없어요.</Text>
          </View>
        )}
      </Card>

      <View style={[s.row, { gap: 10 }]}>
        {SHORTCUTS.map(x => (
          <Pressable key={x.label} onPress={() => router.navigate(x.to)} accessibilityRole="button"
            style={({ pressed }) => [s.card, { flex: 1, alignItems: 'center', gap: 6, paddingHorizontal: 4 }, pressed && s.pressed]}>
            <Icon name={x.icon} size={24} color={C.primary} />
            <Text style={{ fontSize: 12, color: C.text, fontWeight: '600' }}>{x.label}</Text>
          </Pressable>
        ))}
      </View>

      <View style={s.row}>
        <Text style={[s.section, { flex: 1 }]}>발생 중인 재난</Text>
        <Pressable onPress={() => router.push('/disasters')} hitSlop={8}><Text style={[s.link, { marginTop: 8 }]}>전체 목록 ›</Text></Pressable>
      </View>
      {active.length === 0 && <Empty icon="checkmark-circle-outline" text="현재 발생 중인 재난이 없어요." />}
      {active.map(d => (
        <DisasterCard key={d.id} d={d} starred={areas.includes(d.region)} onPress={() => router.push(`/disasters/${d.id}`)} />
      ))}
    </Page>
  );
}
