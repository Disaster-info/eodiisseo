// FS-NOTI-001 재난 알림 설정, FS-NOTI-002 알림 내역 (선택 시 재난 상세로)
import { router } from 'expo-router';
import { Pressable, Switch, Text, View } from 'react-native';
import { Badge, C, Card, Chip, Empty, Header, Icon, Label, Page, notify, s } from '../../components/ui';
import { DISASTERS, LEVELS, NOTI_HISTORY } from '../../data/mock';
import { useApp } from '../../store/AppContext';

export default function NotiScreen() {
  const { noti, setNoti, areas, areaNames } = useApp();
  const targets = noti.areas.filter(a => areas.includes(a)); // 삭제된 관심지역은 자동 제외
  const toggleArea = (a: string) =>
    setNoti(n => ({ ...n, areas: targets.includes(a) ? targets.filter(x => x !== a) : [...targets, a] }));
  const openHistory = (disasterId: number) => {
    if (!DISASTERS.some(d => d.id === disasterId)) return notify('재난 정보를 찾을 수 없어요', '삭제되었거나 보관 기간이 지난 재난입니다.');
    router.push(`/disasters/${disasterId}`);
  };

  return (
    <View style={s.fill}>
      <Header title="재난 알림" />
      <Page>
        <Card>
          <View style={[s.row, { gap: 12 }]}>
            <Icon name="notifications" size={22} color={C.primary} />
            <Text style={[s.bold, { flex: 1 }]}>재난 알림 받기</Text>
            <Switch value={noti.on} onValueChange={on => setNoti(n => ({ ...n, on }))} trackColor={{ true: C.primary }} />
          </View>
        </Card>

        {noti.on && (
          <>
            <Card>
              <Label icon="star" text="알림 받을 관심지역" />
              {areas.length === 0 ? (
                <Pressable onPress={() => router.push('/areas')}><Text style={[s.link, { marginLeft: 0 }]}>관심지역을 먼저 등록하세요 ›</Text></Pressable>
              ) : (
                <View style={[s.row, { flexWrap: 'wrap' }]}>
                  {areas.map(a => <Chip key={a} text={areaNames[a] ?? a} on={targets.includes(a)} onPress={() => toggleArea(a)} />)}
                </View>
              )}
            </Card>
            <Card>
              <Label icon="warning" text="최소 심각도" />
              <View style={s.row}>{LEVELS.map(l => <Chip key={l} text={`${l} 이상`} on={noti.minLevel === l} onPress={() => setNoti(n => ({ ...n, minLevel: l }))} />)}</View>
              <Text style={s.muted}>선택한 등급보다 낮은 재난은 알리지 않아요.</Text>
            </Card>
            <Card>
              <View style={[s.row, { gap: 12 }]}>
                <Icon name="moon" size={20} color={C.sub} />
                <View style={{ flex: 1 }}>
                  <Text style={s.bold}>야간 알림 (22시 이후)</Text>
                  <Text style={s.muted}>끄면 22시 이후에는 알림을 보내지 않아요.</Text>
                </View>
                <Switch value={noti.night} onValueChange={night => setNoti(n => ({ ...n, night }))} trackColor={{ true: C.primary }} />
              </View>
            </Card>
          </>
        )}

        <Text style={s.section}>알림 내역</Text>
        {NOTI_HISTORY.length === 0 && <Empty icon="notifications-off-outline" text="받은 알림이 없어요." />}
        {NOTI_HISTORY.map(h => (
          <Card key={h.id} onPress={() => openHistory(h.disasterId)}>
            <View style={s.row}>
              <Text style={[s.bold, { flex: 1 }]}>{h.title}</Text>
              {!h.ok && <Badge text="발송 실패" color={C.danger} />}
            </View>
            <Text style={s.muted}>{h.at}</Text>
          </Card>
        ))}
      </Page>
    </View>
  );
}
