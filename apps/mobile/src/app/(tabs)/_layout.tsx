// 하단 탭 5개. 로그인 직후 위치 동의가 필요하면 동의 화면으로 보냄 (FS-LOC-001)
import { Redirect } from 'expo-router';
import { Tabs } from 'expo-router/js-tabs';
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

const HomeTitle = () => (
  <View style={s.row}>
    <Icon name="location" size={22} color={C.primary} />
    <Text style={[s.h2, { fontSize: 20 }]}>어디있어</Text>
  </View>
);

export default function TabLayout() {
  const { needConsent } = useApp();
  if (needConsent) return <Redirect href="/consent" />;
  return (
    <Tabs screenOptions={{ tabBarActiveTintColor: C.primary, tabBarInactiveTintColor: C.sub, headerTitleStyle: { fontWeight: '700' } }}>
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
