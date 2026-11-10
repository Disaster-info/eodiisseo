// 없는 주소로 들어왔을 때 (지워진 화면·잘못 입력한 주소). 기본 영어 화면 대신 한국어 안내 + 홈으로
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { Btn, Empty, Header, useTheme } from '../components/ui';

export default function NotFound() {
  const { s } = useTheme();
  return (
    <View style={[s.fill, s.center]}>
      <Header title="페이지 없음" />
      <Empty icon="compass-outline" text={'찾는 화면이 없어요.\n주소가 바뀌었거나 삭제된 화면일 수 있어요.'} />
      <View style={{ alignSelf: 'stretch', maxWidth: 400, width: '100%' }}>
        <Btn title="홈으로" icon="home" onPress={() => router.replace('/')} />
      </View>
      <Text style={[s.small, { marginTop: 12 }]}>문제가 계속되면 앱을 다시 실행해 주세요.</Text>
    </View>
  );
}
