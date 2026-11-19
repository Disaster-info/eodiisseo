// FS-NOTI-001 재난 알림 설정, FS-NOTI-002 알림 내역 (선택 시 재난 상세로)
import { router } from 'expo-router';
import { Pressable, Text, View } from 'react-native';
import { AppSwitch, Card, Chip, Empty, Header, Icon, Label, Page, notify, useTheme } from '../../components/ui';
import { Noti, errText } from '../../data/server';
import { LEVELS } from '../../data/types';
import { useApp } from '../../store/AppContext';

export default function NotiScreen() {
  const { C, s } = useTheme();
  const { noti, notiState, loadNoti, setNoti, areas } = useApp();
  const targets = noti.areaIds.filter(a => areas.some(x => x.id === a)); // 삭제된 관심지역은 자동 제외
  // 서버에 저장. 실패하면 서버 값으로 되돌아가고 안내
  const save = (change: Partial<Noti>) => { setNoti(change).catch(e => notify('알림 설정을 저장하지 못했어요', errText(e))); };
  const toggleArea = (a: string) => save({ areaIds: targets.includes(a) ? targets.filter(x => x !== a) : [...targets, a] });

  return (
    <View style={s.fill}>
      <Header title="재난 알림" />
      <Page>
        {/* 서버 설정을 받기 전·실패면 기본값을 실제 설정처럼 보여 주지 않음 */}
        {notiState !== 'ok' ? (
          <Card onPress={notiState === 'error' ? loadNoti : undefined}>
            <Empty icon={notiState === 'error' ? 'cloud-offline-outline' : 'sync-outline'}
              text={notiState === 'error' ? '알림 설정을 불러오지 못했어요. 눌러서 다시 시도하세요.' : '알림 설정을 불러오는 중…'} />
          </Card>
        ) : <>
        <Card>
          <View style={[s.row, { gap: 12 }]}>
            <Icon name="notifications" size={22} color={C.primary} />
            <Text style={[s.bold, { flex: 1 }]}>재난 알림 받기</Text>
            <AppSwitch accessibilityLabel="재난 알림 받기" value={noti.on} onValueChange={on => save({ on })} />
          </View>
        </Card>

        {noti.on && (
          <>
            <Card>
              <Label icon="star" text="알림 받을 관심지역" />
              {areas.length === 0 ? (
                <Pressable onPress={() => router.push('/areas')} accessibilityRole="button"><View style={s.row}><Text style={[s.link, { marginLeft: 0 }]}>관심지역을 먼저 등록하세요</Text><Icon name="chevron-forward" size={16} color={C.primary} /></View></Pressable>
              ) : (
                <View style={[s.row, { flexWrap: 'wrap' }]}>
                  {areas.map(a => <Chip key={a.id} text={a.name} on={targets.includes(a.id)} onPress={() => toggleArea(a.id)} />)}
                </View>
              )}
            </Card>
            <Card>
              <Label icon="warning" text="최소 심각도" />
              <View style={s.row}>{LEVELS.map(l => <Chip key={l} text={`${l} 이상`} on={noti.minLevel === l} onPress={() => save({ minLevel: l })} />)}</View>
              <Text style={s.muted}>선택한 등급보다 낮은 재난은 알리지 않아요.</Text>
            </Card>
            <Card>
              <View style={[s.row, { gap: 12 }]}>
                <Icon name="moon" size={20} color={C.sub} />
                <View style={{ flex: 1 }}>
                  <Text style={s.bold}>야간 알림 (22시 이후)</Text>
                  <Text style={s.muted}>끄면 22시 이후에는 알림을 보내지 않아요.</Text>
                </View>
                <AppSwitch accessibilityLabel="야간 알림" value={noti.night} onValueChange={night => save({ night })} />
              </View>
            </Card>
          </>
        )}
        </>}

        <Text style={s.section}>알림 내역</Text>
        {/* ponytail: 백엔드에 회원별 알림 내역 조회 API 가 아직 없음. 생기면 목록 + 재난 상세 이동으로 */}
        <Empty icon="notifications-off-outline" text="알림 내역은 아직 볼 수 없어요. 받은 알림은 휴대폰 알림 센터에서 확인하세요." />
      </Page>
    </View>
  );
}
