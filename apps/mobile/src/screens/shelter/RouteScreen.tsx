// FS-ROUTE-001: 대피 경로 — 경로 API 기본 경로 그대로 표시, API 가 준 정보만 보여줌
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, View } from 'react-native';
import { Btn, C, Card, Chip, Header, Icon, InfoRow, MapBox, Page, s } from '../../components/ui';
import { ROUTES, SHELTERS } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function RouteScreen({ id }: { id: number }) {
  const { location, locConsent } = useApp();
  const [useCurrent, setUseCurrent] = useState(locConsent);
  const sh = SHELTERS.find(x => x.id === id);
  if (!sh) return <View style={s.fill}><Header title="대피 경로" /><Text style={s.center}>대피소 정보를 찾을 수 없습니다.</Text></View>;
  const route = ROUTES[sh.id]; // 실제로는 경로 API 호출
  const blocked = useCurrent && !locConsent; // 현재 위치를 쓸 수 없으면 경로 요청 안 함

  return (
    <View style={s.fill}>
      <Header title="대피 경로" />
      <Page>
        <View style={s.row}>
          <Chip text="현재 위치에서" on={useCurrent} onPress={() => setUseCurrent(true)} />
          <Chip text={`지정 위치 (${location})`} on={!useCurrent} onPress={() => setUseCurrent(false)} />
        </View>

        {blocked ? (
          <Card>
            <Text style={s.body}>위치정보 이용에 동의하지 않아 현재 위치를 출발지로 쓸 수 없어요.</Text>
            <View style={{ height: 10 }} />
            <Btn title="출발 위치 직접 지정" icon="pin-outline" outline onPress={() => router.push('/location')} />
          </Card>
        ) : route ? (
          <>
            <MapBox>
              <View style={s.row}>
                <Icon name="radio-button-on" size={16} color={C.success} />
                <Text style={s.bold}>{useCurrent ? '현재 위치' : location}</Text>
                <Icon name="arrow-forward" size={16} color={C.sub} />
                <Icon name="flag" size={16} color={C.danger} />
                <Text style={s.bold}>{sh.name}</Text>
              </View>
              <Text style={s.muted}>경로선 표시 (지도 SDK 연동 예정)</Text>
            </MapBox>
            <Card>
              <InfoRow icon="walk-outline" text={`이동거리 ${route.km}km`} />
              {route.minutes !== undefined && <InfoRow icon="time-outline" text={`예상 소요 ${route.minutes}분 (경로 API 제공)`} />}
            </Card>
          </>
        ) : (
          <Card>
            <View style={s.row}>
              <Icon name="alert-circle-outline" size={20} color={C.danger} />
              <Text style={s.body}>경로를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.</Text>
            </View>
          </Card>
        )}

        <Card>
          <InfoRow icon="shield-checkmark-outline" text={sh.name} />
          <InfoRow icon="location-outline" text={sh.addr} />
        </Card>
        <View style={[s.row, { alignItems: 'flex-start' }]}>
          <Icon name="information-circle-outline" size={16} color={C.warning} />
          <Text style={[s.muted, { flex: 1, marginTop: 0 }]}>지도 서비스가 제공하는 추천 경로이며 안전을 보장하는 최적 경로가 아니에요. 현장 안내를 우선하세요.</Text>
        </View>
      </Page>
    </View>
  );
}
