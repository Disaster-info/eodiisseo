// FS-LOC-001: 위치정보 권한 및 이용 동의
import * as Location from 'expo-location';
import { router } from 'expo-router';
import { Text, View } from 'react-native';
import { Btn, C, Card, Icon, InfoRow, Label, s } from '../../components/ui';
import { useApp } from '../../store/AppContext';

export default function ConsentScreen() {
  const { setLocConsent, setNeedConsent } = useApp();
  return (
    <View style={[s.center, { gap: 12, alignItems: 'stretch' }]}>
      <Icon name="navigate-circle" size={56} color={C.primary} style={{ alignSelf: 'center' }} />
      <Text style={[s.h1, { textAlign: 'center' }]}>위치정보 이용 동의</Text>
      <Card>
        <Label icon="information-circle" text="이용 목적" />
        <InfoRow icon="alert-circle-outline" text="현재 위치 기준 재난정보·날씨·주변 대피소 제공" />
        <InfoRow icon="people-outline" text="안심 그룹 위치공유 (그룹에서 따로 ON 한 경우만)" />
        <View style={s.divider} />
        <Label icon="lock-closed" text="제공 범위" />
        <InfoRow icon="time-outline" text="최신 위치 1건과 갱신 시각만 저장" />
        <Text style={[s.muted, { marginTop: 8 }]}>동의는 내정보 › 위치 설정에서 언제든 철회할 수 있습니다.</Text>
      </Card>
      {/* 앱 동의와 함께 기기(OS) 위치 권한 팝업도 띄움. 거부해도 앱은 지정 위치로 동작 (useGps 가 denied 처리) */}
      <Btn title="동의하고 계속" onPress={() => {
        setLocConsent(true); setNeedConsent(false); router.replace('/');
        Location.requestForegroundPermissionsAsync().catch(() => {});
      }} />
      <Btn title="거부하고 위치 직접 지정" outline onPress={() => { setLocConsent(false); setNeedConsent(false); router.replace('/location'); }} />
    </View>
  );
}
