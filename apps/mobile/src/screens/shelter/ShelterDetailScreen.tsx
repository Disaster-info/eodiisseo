// FS-SHELTER-002: 대피소 상세 — API-SHELTER-002 로 조회, 원본 데이터에 있는 항목만 표시 (없는 값은 줄 자체를 숨김)
import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { ApiError } from '../../api/client';
import { fetchShelterDetail, getListedDistance, SHELTER_TYPE_LABEL, ShelterDetail } from '../../api/shelter';
import PlaceMap from '../../components/PlaceMap';
import { Btn, C, Card, Header, Icon, InfoRow, Page, s } from '../../components/ui';
import { useApp } from '../../store/AppContext';

type State = { status: 'loading' } | { status: 'ok'; sh: ShelterDetail } | { status: 'error'; message: string };

export default function ShelterDetailScreen({ id }: { id: string }) {
  useApp(); // 테마 변경 시 다시 그려지도록 구독
  const [state, setState] = useState<State>({ status: 'loading' });

  const load = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      setState({ status: 'ok', sh: await fetchShelterDetail(id) });
    } catch (e) {
      // SHELTER_NOT_FOUND: DB 연동 전이라 서버 재시작 후엔 목록을 다시 조회해야 찾을 수 있음
      const notFound = e instanceof ApiError && e.code === 'SHELTER_NOT_FOUND';
      setState({ status: 'error', message: notFound ? '대피소 정보를 찾을 수 없어요. 대피소 목록에서 다시 선택해 주세요.'
        : e instanceof ApiError ? e.message : '대피소 정보를 불러오지 못했어요.' });
    }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  if (state.status !== 'ok') {
    return (
      <View style={s.fill}>
        <Header title="대피소" />
        <Page>
          <Card>
            {state.status === 'loading' ? (
              <View style={s.row}><ActivityIndicator color={C.primary} /><Text style={s.body}>대피소 정보를 불러오는 중이에요…</Text></View>
            ) : (
              <>
                <View style={s.row}>
                  <Icon name="alert-circle-outline" size={20} color={C.danger} />
                  <Text style={[s.body, { flex: 1 }]}>{state.message}</Text>
                </View>
                <View style={{ height: 10 }} />
                <Btn title="다시 시도" icon="refresh" outline onPress={load} />
              </>
            )}
          </Card>
        </Page>
      </View>
    );
  }

  const sh = state.sh;
  const distance = getListedDistance(sh.id); // 목록에서 본 직선거리 (있을 때만)
  return (
    <View style={s.fill}>
      <Header title={sh.name} />
      <Page>
        {/* 대피소 위치 핀 하나 (경로선 없음) */}
        <PlaceMap pins={[{ lat: sh.lat, lng: sh.lng, title: sh.name, kind: 'end' }]} height={220} />
        <Card>
          <InfoRow icon="location-outline" text={sh.address} />
          <InfoRow icon="business-outline" text={SHELTER_TYPE_LABEL[sh.type] ?? sh.type} />
          {sh.capacity != null && <InfoRow icon="people-outline" text={`수용 가능 인원 ${sh.capacity.toLocaleString()}명`} />}
          {sh.isUnderground != null && <InfoRow icon="layers-outline" text={sh.isUnderground ? '지하 시설' : '지상 시설'} />}
          {distance != null && <InfoRow icon="resize-outline" text={`기준 위치에서 직선거리 ${(distance / 1000).toFixed(1)}km`} />}
          {/* FR-SHELTER-012: 출처와 확인 가능한 갱신정보. 원본에 갱신시각이 없으면 만들지 않고 "없음"으로 표시 */}
          <Text style={[s.muted, { marginTop: 8 }]}>
            출처: {sh.organization} · {sh.updatedAt ? `데이터 갱신 ${sh.updatedAt.slice(0, 10)}` : '갱신정보 없음'}
          </Text>
        </Card>
        <Btn title="대피 경로 보기" icon="navigate" onPress={() => router.push(`/route/${sh.id}`)} />
      </Page>
    </View>
  );
}
