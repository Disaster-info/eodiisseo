// FS-OPS-000 접근 권한, 001 운영 상태, 002 재난 데이터·오류 로그 (운영자 전용)
import { useState } from 'react';
import { Text, View } from 'react-native';
import { Badge, C, Card, Chip, Empty, Header, Icon, Label, Page, s } from '../../components/ui';
import { DISASTERS, OPS_APIS, OPS_ERRORS, OPS_MOCK_API } from '../../data/mock';
import { useApp } from '../../store/AppContext';

const TYPES = ['전체', '지진', '호우', '산불'];
const STATUSES = ['전체', '발생', '진행중', '종료', '해제'];
// ok: null(확인 불가)을 정상으로 표시하지 않음
const apiBadge = (ok: boolean | null) =>
  ok === null ? <Badge text="확인 불가" color={C.sub} /> : ok ? <Badge text="정상" icon="checkmark-circle" color={C.success} /> : <Badge text="실패" icon="close-circle" color={C.danger} />;

export default function OpsScreen() {
  const { member } = useApp();
  const [type, setType] = useState('전체');
  const [status, setStatus] = useState('전체');
  if (!member?.operator) return (
    <View style={s.fill}><Header title="운영 관리" /><Empty icon="lock-closed-outline" text="운영 관리 권한이 없어요." /></View>
  );
  const data = DISASTERS.filter(d => (type === '전체' || d.type === type) && (status === '전체' || d.status === status));

  return (
    <View style={s.fill}>
      <Header title="운영 관리" />
      <Page>
        <Card>
          <View style={s.row}>
            <Label icon="server" text="데이터 환경" />
            <View style={{ marginLeft: 'auto', marginBottom: 6 }}>
              {OPS_MOCK_API === null ? <Badge text="확인 불가" color={C.sub} /> : <Badge text={OPS_MOCK_API ? 'Mock API' : '실제 API'} color={OPS_MOCK_API ? C.warning : C.success} />}
            </View>
          </View>
        </Card>

        <Text style={s.section}>외부 API 수집 상태</Text>
        {OPS_APIS.map(a => (
          <Card key={a.name}>
            <View style={s.row}><Text style={[s.bold, { flex: 1 }]}>{a.name}</Text>{apiBadge(a.ok)}</View>
            <Text style={s.muted}>마지막 정상 수집 {a.lastOk ?? '기록 없음'}{a.failAt ? ` · 최근 실패 ${a.failAt}` : ''}</Text>
          </Card>
        ))}

        <Text style={s.section}>재난 데이터</Text>
        <View style={[s.row, { flexWrap: 'wrap' }]}>{TYPES.map(t => <Chip key={t} text={t} on={type === t} onPress={() => setType(t)} />)}</View>
        <View style={[s.row, { flexWrap: 'wrap' }]}>{STATUSES.map(t => <Chip key={t} text={t} on={status === t} onPress={() => setStatus(t)} />)}</View>
        {data.length === 0 && <Empty icon="search-outline" text="조건에 맞는 데이터가 없어요." />}
        {data.map(d => (
          <Card key={d.id}>
            <Text style={s.bold}>#{d.id} {d.type} · {d.region}</Text>
            <Text style={s.muted}>{d.level ?? '등급 없음'} · {d.status} · 발생 {d.occurredAt} · 갱신 {d.updatedAt} · AI {d.ai.status}</Text>
          </Card>
        ))}

        <Text style={s.section}>오류 로그</Text>
        {OPS_ERRORS.length === 0 && <Empty icon="checkmark-done-outline" text="기록된 오류가 없어요." />}
        {OPS_ERRORS.map((e, i) => (
          <Card key={i}>
            <View style={s.row}><Icon name="bug-outline" size={16} color={C.danger} /><Text style={[s.bold, { flex: 1 }]}>{e.target}</Text></View>
            <Text style={s.muted}>{e.at} · {e.cause}</Text>
          </Card>
        ))}
      </Page>
    </View>
  );
}
