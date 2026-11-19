// FS-OPS: 운영 공지 발송 (운영자 전용)
// ponytail: 백엔드에 운영 공지 발송·이력 API 가 아직 없음 → 보낸 것처럼 보이지 않게 안내만. API 가 생기면 대상 지역·등급·제목·내용 양식 + 발송 이력으로
import { Text, View } from 'react-native';
import { Card, Empty, Header, Page, useTheme } from '../../components/ui';
import { useApp } from '../../store/AppContext';

export default function NoticeScreen() {
  const { s } = useTheme();
  const { member } = useApp();
  if (!member?.operator) return null; // 라우터가 운영자에게만 열지만 한 번 더 막음
  return (
    <View style={s.fill}>
      <Header title="운영 공지 보내기" />
      <Page>
        <Card><Empty icon="megaphone-outline" text={'운영 공지 발송 기능은 아직 서버에 준비되지 않았어요.\n긴급 안내는 공식 재난문자 체계를 이용하세요.'} /></Card>
        <Text style={s.small}>서버에 공지 발송 API 가 추가되면 이 화면에서 대상 지역·등급을 골라 보낼 수 있어요.</Text>
      </Page>
    </View>
  );
}
