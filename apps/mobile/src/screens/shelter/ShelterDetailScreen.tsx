// FS-SHELTER-002: 대피소 상세 — 원본 데이터에 있는 항목만 표시
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { Btn, Card, Header, InfoRow, MapBox, Page, s } from '../../components/ui';
import { SHELTERS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function ShelterDetailScreen({ id }: { id: number }) {
  useApp(); // 테마 변경 시 다시 그려지도록 구독
  const sh = SHELTERS.find(x => x.id === id);
  if (!sh) return <View style={s.fill}><Header title="대피소" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  return (
    <View style={s.fill}>
      <Header title={sh.name} />
      <Page>
        <MapBox><Text style={s.muted}>대피소 위치 지도 (지도 SDK 연동 예정)</Text></MapBox>
        <Card>
          <InfoRow icon="location-outline" text={sh.addr} />
          <InfoRow icon="business-outline" text={sh.type} />
          {sh.capacity !== undefined && <InfoRow icon="people-outline" text={`수용 인원 ${sh.capacity.toLocaleString()}명`} />}
          <InfoRow icon="resize-outline" text={`기준 위치에서 직선거리 ${sh.distance}km`} />
          <Text style={[s.muted, { marginTop: 8 }]}>출처: 행정안전부 · {sh.updatedAt ? `데이터 갱신 ${sh.updatedAt}` : '갱신정보 없음'}</Text>
        </Card>
        <Btn title="대피 경로 보기" icon="navigate" onPress={() => router.push(`/route/${sh.id}`)} />
      </Page>
    </View>
  );
}
