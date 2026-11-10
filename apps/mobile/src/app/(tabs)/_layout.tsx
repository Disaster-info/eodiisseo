// 하단 탭 5개. 위치 동의·기준 위치가 준비된 뒤에만 열림 (조건은 루트 _layout 의 Stack.Protected, FS-LOC-001)
import { Tabs } from 'expo-router/js-tabs';
import { getDefaultHeaderHeight } from 'expo-router/react-navigation';
import { useSafeAreaFrame, useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, IconName, useLayout, useTheme } from '../../components/ui';
import { goHomeFirstPage } from '../../screens/home/HomeScreen';
import { useApp } from '../../store/AppContext';

// 선택된 탭은 채워진 아이콘, 나머지는 외곽선 아이콘
const TABS: { name: string; title: string; icon: IconName; on: IconName }[] = [
  { name: 'index', title: '홈', icon: 'home-outline', on: 'home' },
  { name: 'shelter', title: '대피소', icon: 'shield-outline', on: 'shield' },
  { name: 'group', title: '그룹', icon: 'people-outline', on: 'people' },
  { name: 'checklist', title: '체크리스트', icon: 'checkbox-outline', on: 'checkbox' },
  { name: 'my', title: '내정보', icon: 'person-circle-outline', on: 'person-circle' },
];

const TITLE_SIZE = 22;
// 혼합 디자인: 홈 = D안(전면 상황판, 헤더 없이 화면 전체가 상태 색 · 어두운 탭 바), 나머지 탭 = C안(페트롤 블루 헤더 + 회청 바탕)
// 홈의 기준 위치 표시는 홈 화면 맨 위 줄이 맡음
const HOME_BAR = { bg: '#0B0D10', border: '#22262B', on: '#F4EFEA', off: '#9A9EA6' };

export default function TabLayout() {
  const { C } = useTheme();
  const frame = useSafeAreaFrame();
  // 본문과 같은 좌우 기준: 화면 크기별 여백 (useLayout)
  const { gutter } = useLayout();
  const { top } = useSafeAreaInsets();
  const { member } = useApp();
  // FS-OPS: 운영자 계정은 내정보 탭이 '관리자 설정', 개인용 안심그룹·체크리스트 탭은 숨김 (홈·대피소는 사용자 화면 점검용으로 유지)
  const ops = !!member?.operator;
  const tabs = ops ? TABS.map(t => (t.name === 'my' ? { ...t, title: '관리자 설정', icon: 'settings-outline' as IconName, on: 'settings' as IconName } : t)) : TABS;
  const hidden = (name: string) => ops && (name === 'group' || name === 'checklist');
  const barStyle = (bg: string, border: string) => ({ backgroundColor: bg, borderTopColor: border, borderTopWidth: 1, paddingHorizontal: gutter > 16 ? gutter : 0 });
  return (
    <Tabs screenOptions={{
      tabBarHideOnKeyboard: true,
      // 탭 5개를 본문과 같은 칸에: 넓은 화면(여백 32·48)에서만 좌우 여백, 휴대폰은 0
      tabBarStyle: barStyle(C.bar, C.barBorder),
      tabBarActiveTintColor: C.tabOn,
      tabBarInactiveTintColor: C.sub,
      headerTitleAlign: 'left', // iOS 기본값(가운데)과 안드로이드를 왼쪽으로 통일
      headerTitleContainerStyle: { marginLeft: gutter }, // 제목 시작선 = 본문 시작선
      headerTitleStyle: { fontSize: TITLE_SIZE, fontWeight: '800', color: '#FFFFFF' },
      // 기본 높이에 위아래 6px 씩 여백 추가 · 페트롤 블루 헤더
      headerStyle: { height: getDefaultHeaderHeight(frame, false, top) + 12, backgroundColor: C.head },
      headerShadowVisible: false, // 헤더 색이 본문 위 띠와 이어지게
      headerTintColor: '#FFFFFF',
    }}>
      {tabs.map(t => (
        <Tabs.Screen key={t.name} name={t.name} options={{
          title: t.title,
          href: hidden(t.name) ? null : undefined,
          // 홈: 헤더 없음 + 어두운 탭 바 (D안)
          ...(t.name === 'index' && { headerShown: false, tabBarStyle: barStyle(HOME_BAR.bg, HOME_BAR.border), tabBarActiveTintColor: HOME_BAR.on, tabBarInactiveTintColor: HOME_BAR.off }),
          tabBarIcon: ({ focused, color, size }) => <Icon name={focused ? t.on : t.icon} size={size} color={color} />,
        }}
        // 홈 탭을 누르면 (다른 탭에서 와도, 이미 홈이어도) 홈 1쪽으로
        listeners={t.name === 'index' ? { tabPress: goHomeFirstPage } : undefined} />
      ))}
    </Tabs>
  );
}
