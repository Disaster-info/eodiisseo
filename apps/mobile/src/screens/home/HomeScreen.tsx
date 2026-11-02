// 대시보드: FS-DASH-001 주요 재난·바로가기, FS-WEATHER-001 날씨, 관심지역 재난 우선 표시
// 읽는 순서 = 지금 위험한가(상태 판) → 어디로 가나(대피 안내판) → 가족은 괜찮나(안심그룹) → 다른 지역 → 날씨
import { Href, router } from 'expo-router';
import { useState } from 'react';
import { AccessibilityInfo, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import Animated, { Easing, FadeIn, FadeInUp, useReducedMotion } from 'react-native-reanimated';
import { DisasterCard } from '../../components/disaster';
import { ShelterSign } from '../../components/shelter';
import { Card, Chip, Icon, IconName, Page, Sign, knownImpact, useTheme } from '../../components/ui';
import { DISASTERS, ME, WEATHER, WEATHER_ICON, WEATHER_TIP, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const CURRENT = '현재 위치';
const MORE: { icon: IconName; label: string; to: Href }[] = [
  { icon: 'chatbubbles-outline', label: '행동요령 물어보기', to: '/chat' },
  { icon: 'notifications-outline', label: '재난 알림 설정·내역', to: '/noti' },
];

export default function HomeScreen() {
  const { C, s } = useTheme();
  const { location, isManual, areas, areaNames, groups } = useApp();
  const [picked, setPicked] = useState(CURRENT);
  const reduceMotion = useReducedMotion();
  // 아래로 당겨 새로고침. ponytail: 목업이라 잠시 기다린 뒤 끝냄 — 백엔드 연동 시 재난·날씨·그룹 위치 재조회로 교체
  const [refreshing, setRefreshing] = useState(false);
  const refresh = () => {
    setRefreshing(true);
    setTimeout(() => { setRefreshing(false); AccessibilityInfo.announceForAccessibility('재난 정보를 새로고침했어요'); }, 800);
  };

  const mine = (r: string) => r === location || areas.includes(r);
  const active = DISASTERS.filter(isActive);
  // 비상 모드: 기준 위치·관심지역에 진행 중인 '경보'. 기준 위치 것을 먼저
  const alert = active.filter(d => d.level === '경보' && mine(d.region)).sort((a, b) => Number(b.region === location) - Number(a.region === location))[0];
  const rest = active.filter(d => d !== alert).sort((a, b) => Number(mine(b.region)) - Number(mine(a.region)));

  // 날씨: 기준 위치 / 관심지역 선택 (없으면 조회 실패 — 임의 값을 만들지 않음)
  const others = areas.filter(a => a !== location);
  const base = others.includes(picked) ? picked : CURRENT;
  const region = base === CURRENT ? location : base;
  const w = WEATHER[region];
  const tip = w && WEATHER_TIP[w.cond];

  return (
    <Page refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={C.primary} colors={[C.fill]} progressBackgroundColor={C.card} title="재난 정보 새로고침" />}>
      {/* 1. 지금 위험한가 */}
      {alert ? (
        // key: 다른 경보로 바뀔 때만 다시 재생. FadeInUp = 위(-25)에서 내려오며 나타남 (reanimated 이름은 방향이 반대처럼 읽힘)
        <Animated.View key={alert.id} entering={reduceMotion ? FadeIn.duration(200) : FadeInUp.duration(360).easing(Easing.out(Easing.exp))}>
          <DisasterCard d={alert} starred={areas.includes(alert.region)} onPress={() => router.push(`/disasters/${alert.id}`)} />
        </Animated.View>
      ) : (
        <Sign tone="blue">
          <View style={s.row}>
            <Icon name="checkmark-circle" size={28} color={C.onSign} />
            <Text style={[s.h2, { color: C.onSign, flex: 1, fontWeight: '900' }]}>발령 중인 경보 없음</Text>
          </View>
          <Text style={[s.body, { color: C.onSign }]}>
            {location}{others.length ? ` · 관심지역 ${others.length}곳` : ''} 기준 · {rest.some(d => mine(d.region)) ? '주의보는 아래에서 확인하세요.' : '발생 중인 재난이 없어요.'}
          </Text>
        </Sign>
      )}

      {/* 2. 어디로 가나 — 판 하나에 결정 하나 */}
      <ShelterSign region={location} />

      {/* 3. 가족은 괜찮나 — 그룹마다 한 줄 */}
      <View style={[s.card, { paddingVertical: 4 }]}>
        {groups.length === 0 && (
          <Pressable onPress={() => router.navigate('/group')} accessibilityRole="button" style={({ pressed }) => [s.row, { minHeight: 52 }, pressed && s.pressed]}>
            <Icon name="people-outline" size={22} color={C.primary} /><Text style={[s.bold, { flex: 1 }]}>안심그룹 만들기</Text><Icon name="chevron-forward" size={18} color={C.sub} />
          </Pressable>
        )}
        {groups.map((g, n) => {
          const ms = g.members.filter(m => m.id !== ME);
          const hit = ms.filter(m => knownImpact(m) === '영향지역 포함').length;
          const out = ms.filter(m => knownImpact(m) === '영향지역 밖').length;
          const unknown = ms.length - hit - out; // 위치정보 미제공 · 판단 불가 · 오래된 위치
          const rest = !ms.length ? '아직 다른 구성원이 없어요' : [out && `${out}명 밖`, unknown && `${unknown}명 확인 필요`].filter(Boolean).join(' · ');
          return (
            <Pressable key={g.id} onPress={() => router.push(`/groups/${g.id}`)} accessibilityRole="button"
              style={({ pressed }) => [s.row, { minHeight: 52, paddingVertical: 8 }, n > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.border }, pressed && s.pressed]}>
              <Icon name={hit ? 'warning' : 'people'} size={22} color={hit ? C.danger : C.primary} />
              <Text style={[s.body, { flex: 1 }]} numberOfLines={2}>
                <Text style={{ fontWeight: '700' }}>{g.name}</Text>
                {hit > 0 && <Text style={{ color: C.danger, fontWeight: '700' }}> · {hit}명 영향지역</Text>}
                {!!rest && <Text style={{ color: C.sub }}> · {rest}</Text>}
              </Text>
              <Icon name="chevron-forward" size={18} color={C.sub} />
            </Pressable>
          );
        })}
      </View>

      {/* 4. 다른 지역 */}
      <View style={[s.row, { marginTop: 8 }]}>
        <Text style={[s.section, { flex: 1, marginTop: 0 }]}>{alert ? '그 밖에 발생 중' : '발생 중인 재난'}</Text>
        <Pressable onPress={() => router.push('/disasters')} hitSlop={12} accessibilityRole="button" accessibilityLabel="재난 전체 목록"><View style={s.row}><Text style={s.link}>전체 목록</Text><Icon name="chevron-forward" size={16} color={C.primary} /></View></Pressable>
      </View>
      {rest.length === 0 && <Text style={s.muted}>다른 지역에 발생 중인 재난이 없어요.</Text>}
      {rest.map(d => (
        <DisasterCard key={d.id} d={d} starred={areas.includes(d.region)} onPress={() => router.push(`/disasters/${d.id}`)} />
      ))}

      {/* 5. 날씨 — 보조 정보 */}
      <Card>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
          {[CURRENT, ...others].map(a => <Chip key={a} text={a === CURRENT ? (isManual ? '지정 위치' : '현재 위치') : areaNames[a] ?? a} on={base === a} onPress={() => setPicked(a)} />)}
          {others.length === 0 && <Chip text="관심지역 추가" on={false} onPress={() => router.push('/areas')} />}
        </ScrollView>
        {w ? (
          <View style={[s.row, { marginTop: 6, gap: 12, alignItems: 'flex-start' }]}>
            <Icon name={WEATHER_ICON[w.cond]} size={28} color={C.primary} />
            <View style={{ flex: 1 }}>
              <Text style={s.bold}>{w.cond} · <Text style={s.num}>{w.temp}°</Text></Text>
              {!!tip && <Text style={s.body} lineBreakStrategyIOS="hangul-word">{tip}</Text>}
              <Text style={s.small}>{region} · {w.at} 기준</Text>
            </View>
          </View>
        ) : (
          <View style={[s.row, { marginTop: 6 }]}>
            <Icon name="cloud-offline-outline" size={22} color={C.sub} />
            <Text style={[s.muted, { flex: 1 }]}>{region}의 날씨 정보를 확인할 수 없어요.</Text>
          </View>
        )}
      </Card>

      <View style={[s.card, { paddingVertical: 4 }]}>
        {MORE.map((x, i) => (
          <Pressable key={x.label} onPress={() => router.push(x.to)} accessibilityRole="button"
            style={({ pressed }) => [s.row, { minHeight: 52, gap: 12 }, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: C.border }, pressed && s.pressed]}>
            <Icon name={x.icon} size={22} color={C.primary} />
            <Text style={[s.body, { flex: 1 }]}>{x.label}</Text>
            <Icon name="chevron-forward" size={18} color={C.sub} />
          </Pressable>
        ))}
      </View>
    </Page>
  );
}
