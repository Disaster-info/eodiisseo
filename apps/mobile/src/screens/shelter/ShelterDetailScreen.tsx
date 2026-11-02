// FS-SHELTER-002: 대피소 상세 — 원본 데이터에 있는 항목만 표시
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { TmapView } from '../../components/map';
import { Btn, Card, Header, InfoRow, Page, useTheme } from '../../components/ui';
import { SHELTERS } from '../../data/mock';

export default function ShelterDetailScreen({ id }: { id: number }) {
  const { s } = useTheme();
  const sh = SHELTERS.find(x => x.id === id);
  if (!sh) return <View style={s.fill}><Header title="대피소" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  return (
    <View style={s.fill}>
      <Header title={sh.name} />
      <Page>
        <TmapView markers={[{ lat: sh.lat, lon: sh.lon, title: sh.name }]} label={`${sh.name} 위치 지도`} />
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
