// 하단 탭 5개. 로그인 직후 위치 동의가 필요하면 동의 화면으로 보냄 (FS-LOC-001)
import { Redirect } from 'expo-router';
import { Tabs } from 'expo-router/js-tabs';
import { getDefaultHeaderHeight } from 'expo-router/react-navigation';
import { useSafeAreaFrame, useSafeAreaInsets } from 'react-native-safe-area-context';
import { Text, View } from 'react-native';
import { C, Icon, IconName, s } from '../../components/ui';
import { useApp } from '../../store/AppContext';

// 선택된 탭은 채워진 아이콘, 나머지는 외곽선 아이콘
const TABS: { name: string; title: string; icon: IconName; on: IconName }[] = [
  { name: 'index', title: '홈', icon: 'home-outline', on: 'home' },
  { name: 'shelter', title: '대피소', icon: 'shield-outline', on: 'shield' },
  { name: 'group', title: '안심그룹', icon: 'people-outline', on: 'people' },
  { name: 'checklist', title: '체크리스트', icon: 'checkbox-outline', on: 'checkbox' },
  { name: 'my', title: '내정보', icon: 'person-circle-outline', on: 'person-circle' },
];

const TITLE_SIZE = 22;
// 홈 헤더: 앱 이름 대신 기준 위치 (GPS 현재 위치 / 직접 지정한 위치)
function HomeTitle() {
  const { location, isManual } = useApp();
  return (
    <View style={s.row}>
      <Icon name={isManual ? 'pin' : 'navigate'} size={20} color={C.primary} />
      <Text style={[s.h2, { fontSize: TITLE_SIZE, fontWeight: '800' }]}>{location}</Text>
      <Text style={[s.muted, { marginTop: 4 }]}>{isManual ? '직접 지정' : '현재 위치'}</Text>
    </View>
  );
}

export default function TabLayout() {
  const { needConsent } = useApp();
  const frame = useSafeAreaFrame();
  const { top } = useSafeAreaInsets();
  if (needConsent) return <Redirect href="/consent" />;
  return (
    <Tabs screenOptions={{
      tabBarHideOnKeyboard: true,
      tabBarStyle: { backgroundColor: C.bar, borderTopColor: C.barBorder, borderTopWidth: 1 },
      tabBarActiveTintColor: C.primary,
      tabBarInactiveTintColor: C.sub,
      headerTitleAlign: 'left', // iOS 기본값(가운데)과 안드로이드를 왼쪽으로 통일
      headerStyle: { height: getDefaultHeaderHeight(frame, false, top) + 12 }, // 기본 높이에 위아래 6px 씩 여백 추가
      headerTitleStyle: { fontSize: TITLE_SIZE, fontWeight: '800' },
    }}>
      {TABS.map(t => (
        <Tabs.Screen key={t.name} name={t.name} options={{
          title: t.title,
          headerTitle: t.name === 'index' ? HomeTitle : t.title,
          tabBarIcon: ({ focused, color, size }) => <Icon name={focused ? t.on : t.icon} size={size} color={color} />,
        }} />
      ))}
    </Tabs>
  );
}
