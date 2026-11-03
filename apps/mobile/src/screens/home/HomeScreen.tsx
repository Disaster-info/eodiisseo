// 대시보드: FS-WEATHER-001 날씨, FS-DASH-001 주요 재난, 관심지역 재난 우선 표시
// 읽는 순서 = 내 위치 날씨(+ 내 지역 재난 버튼: 경보 → 주의보 → 정보, 없으면 안심 표시) → 가족은 괜찮나(안심그룹) → 다른 지역(한 줄 목록)
// 행동요령 물어보기는 재난 상세에서 연결
import { router } from 'expo-router';
import { useState } from 'react';
import { AccessibilityInfo, Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';
import { DisasterRow } from '../../components/disaster';
import { Card, Chip, Icon, Page, IconMark, LEVEL_TONE, knownImpact, useTheme } from '../../components/ui';
import { DISASTERS, Disaster, LEVEL_ICON, ME, TYPE_ICON, WEATHER, WEATHER_ICON, WEATHER_TIP, isActive } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const CURRENT = '현재 위치';
const FAR_MAX = 3; // 다른 지역은 한 줄씩 최대 3건, 나머지는 '외 N건'
const RANK = { 경보: 0, 주의보: 1, 정보: 2 } as const;
const rank = (d: Disaster) => (d.level ? RANK[d.level] : 3);

export default function HomeScreen() {
  const { C, s } = useTheme();
  const { location, isManual, areas, areaNames, groups } = useApp();
  const [picked, setPicked] = useState(CURRENT);
  // 아래로 당겨 새로고침. ponytail: 목업이라 잠시 기다린 뒤 끝냄 — 백엔드 연동 시 재난·날씨·그룹 위치 재조회로 교체
  const [refreshing, setRefreshing] = useState(false);
  const refresh = () => {
    setRefreshing(true);
    setTimeout(() => { setRefreshing(false); AccessibilityInfo.announceForAccessibility('재난 정보를 새로고침했어요'); }, 800);
  };

  const mine = (r: string) => r === location || areas.includes(r);
  const active = DISASTERS.filter(isActive);
  // 내 지역(현재 위치·관심지역)에 진행 중인 재난 → 모두 날씨 칩 줄 맨 왼쪽에 같은 크기 버튼으로 (없으면 안 보임)
  // 위험한 것부터: 경보 → 주의보 → 정보 → 등급 없음. 같은 등급이면 현재 위치 먼저 (위험도가 크기로 뒤집히지 않게)
  const near = active.filter(d => mine(d.region))
    .sort((a, b) => rank(a) - rank(b) || Number(b.region === location) - Number(a.region === location));
  // 버튼 글자: 현재 위치는 '호우경보', 관심지역은 '수원시 호우주의보' (별칭이 있으면 별칭)
  const nearLabel = (d: Disaster) => `${d.region === location ? '' : `${areaNames[d.region] ?? d.region.split(' ').pop()} `}${d.type}${d.level ?? ''}`;
  const elsewhere = active.filter(d => !mine(d.region));

  // 날씨: 기준 위치 / 관심지역 선택 (없으면 조회 실패 — 임의 값을 만들지 않음)
  const others = areas.filter(a => a !== location);
  const base = others.includes(picked) ? picked : CURRENT;
  const region = base === CURRENT ? location : base;
  const w = WEATHER[region];
  const tip = w && WEATHER_TIP[w.cond];

  return (
    <Page refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={C.primary} colors={[C.fill]} progressBackgroundColor={C.card} title="재난 정보 새로고침" />}>
      {/* 1. 내 위치 날씨 — 내 지역 재난은 지역 칩 맨 왼쪽에 위험한 순서로 버튼 */}
      <Card>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
          {near.map(d => {
            const tone = d.level ? LEVEL_TONE[d.level] : null; // 등급 없음은 중립 칩
            const bg = tone ? C.sign[tone] : C.card;
            const ink = tone ? C.signInk[tone] : C.text;
            // 테두리: 판에 흰 안쪽 테두리를 쓰는 색이면 바탕과 같은 색, 다크(어두운 면)면 색 테두리
            const line = !tone ? C.border : C.signLine[tone] === C.onSign ? bg : C.signLine[tone];
            return (
              <Pressable key={d.id} onPress={() => router.push(`/disasters/${d.id}`)} accessibilityRole="button"
                accessibilityLabel={`${d.region} ${d.type}${d.level ?? ''} ${d.status}. 자세히 보기`}
                style={({ pressed }) => [s.chip, s.row, { gap: 4, borderColor: line, backgroundColor: bg }, pressed && s.pressed]}>
                <Icon name={d.level ? LEVEL_ICON[d.level] : TYPE_ICON[d.type]} size={d.level === '경보' ? 22 : 18} color={ink} />
                <Text style={[s.chipText, { color: ink, fontWeight: '700' }]} maxFontSizeMultiplier={1.4}>{nearLabel(d)}</Text>
              </Pressable>
            );
          })}
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
        {near.length === 0 && (
          <View style={[s.row, { marginTop: 10, gap: 6 }]} accessible>
            <Icon name="checkmark-circle" size={18} color={C.success} />
            <Text style={[s.small, { flex: 1 }]}>내 지역에 발생 중인 재난이 없어요 · {location}{others.length ? ` · 관심지역 ${others.length}곳` : ''} 기준</Text>
          </View>
        )}
      </Card>

      {/* 2. 가족은 괜찮나 — 그룹마다 한 줄 */}
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
              style={({ pressed }) => [s.row, { minHeight: 52, paddingVertical: 8 }, n > 0 && s.divTop, pressed && s.pressed]}>
              <IconMark name={hit ? 'warning' : 'people'} color={hit ? C.danger : C.primary} />
              <Text style={[s.body, { flex: 1 }]} numberOfLines={2} lineBreakStrategyIOS="hangul-word">
                <Text style={{ fontWeight: '700' }}>{g.name}</Text>
                {hit > 0 && <Text style={{ color: C.danger, fontWeight: '700' }}> · {hit}명 영향지역</Text>}
                {!!rest && <Text style={{ color: C.sub }}> · {rest}</Text>}
              </Text>
              <Icon name="chevron-forward" size={18} color={C.sub} />
            </Pressable>
          );
        })}
      </View>

      {/* 3. 다른 지역 재난 — 한 줄씩 작게 (맨 아래) */}
      <View style={[s.row, { marginTop: 12, minHeight: 44 }]}>
        <Text style={[s.section, { flex: 1, marginTop: 0 }]} accessibilityRole="header">다른 지역</Text>
        <Pressable onPress={() => router.push('/disasters')} hitSlop={12} accessibilityRole="button" accessibilityLabel="재난 전체 목록">
          <View style={s.row}><Text style={[s.link, { marginLeft: 0 }]}>전체 목록</Text><Icon name="chevron-forward" size={16} color={C.primary} /></View>
        </Pressable>
      </View>
      {elsewhere.length === 0 ? (
        <Text style={[s.muted, { marginTop: 0 }]}>다른 지역에 발생 중인 재난이 없어요.</Text>
      ) : (
        <View style={[s.card, { paddingVertical: 2 }]}>
          {elsewhere.slice(0, FAR_MAX).map((d, i) => (
            <DisasterRow key={d.id} d={d} divider={i > 0} onPress={() => router.push(`/disasters/${d.id}`)} />
          ))}
          {elsewhere.length > FAR_MAX && (
            <Pressable onPress={() => router.push('/disasters')} accessibilityRole="button"
              style={({ pressed }) => [s.row, s.divTop, { minHeight: 48, justifyContent: 'center' }, pressed && s.pressed]}>
              <Text style={[s.link, { marginLeft: 0, paddingVertical: 0 }]}>외 {elsewhere.length - FAR_MAX}건 더 보기</Text>
            </Pressable>
          )}
        </View>
      )}
    </Page>
  );
}
