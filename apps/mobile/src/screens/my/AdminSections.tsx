// FS-OPS: 관리자 설정 탭 메인의 운영 현황 (운영자 전용, 백엔드 /api/v1/ops/*)
// 운영 상태(+수동 재수집) → 운영 공지 → 재난 데이터 → 오류 로그
// 이용 통계·알림 발송 현황은 백엔드 API 가 없어 보여 주지 않음 (숫자를 지어내지 않음)
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { Badge, Chip, Empty, IconName, confirm, notify, useTheme, type Colors } from '../../components/ui';
import { errText, opsApi, stamp, useLoad } from '../../data/server';
import { DisasterType, TYPES, TYPE_ICON } from '../../data/types';
import { Group, Row } from './rows';

const STATUSES = [['전체', undefined], ['발생', 'OCCURRED'], ['진행중', 'ONGOING'], ['종료', 'ENDED'], ['해제', 'LIFTED']] as const;

// 수집 실행 상태 → 배지. 모르는 값·기록 없음은 정상으로 표시하지 않음
function apiBadge(C: Colors, status: string) {
  if (status === 'SUCCESS') return <Badge text="정상" icon="checkmark-circle" color={C.success} />;
  if (status === 'FAILED') return <Badge text="실패" icon="close-circle" color={C.danger} />;
  if (status === 'RUNNING' || status === 'QUEUED' || status === 'ACCEPTED') return <Badge text="수집 중" color={C.warning} />;
  return <Badge text="확인 불가" color={C.sub} />;
}

function SmallAction({ title, busy, onPress, label }: { title: string; busy: boolean; onPress: () => void; label: string }) {
  const { C, s } = useTheme();
  if (busy) return <ActivityIndicator color={C.primary} accessibilityLabel={`${label} 진행 중`} />;
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={label} hitSlop={6}
      style={({ pressed }) => [{ minHeight: 36, paddingHorizontal: 12, borderRadius: 999, justifyContent: 'center', backgroundColor: C.primarySoft }, pressed && s.pressed]}>
      <Text style={[s.small, { color: C.primary, fontWeight: '700' }]}>{title}</Text>
    </Pressable>
  );
}

// 불러오는 중·실패 한 줄
function LoadRow({ loading, error, reload }: { loading: boolean; error?: string; reload: () => void }) {
  const { C } = useTheme();
  if (loading) return <ActivityIndicator color={C.primary} style={{ paddingVertical: 10 }} />;
  if (error) return <Row icon="cloud-offline-outline" title="불러오지 못했어요" sub={error} danger onPress={reload} right={<Badge text="다시 시도" color={C.primary} />} />;
  return null;
}

export function AdminSections() {
  const { C, s } = useTheme();
  const [busy, setBusy] = useState<string | null>(null); // 진행 중인 재수집 (한 번에 하나)
  const [type, setType] = useState<DisasterType | '전체'>('전체');
  const [status, setStatus] = useState<(typeof STATUSES)[number][0]>('전체');
  const [openErr, setOpenErr] = useState<number | null>(null); // 펼쳐 본 오류 로그
  const ops = useLoad('status', opsApi.status);
  const errors = useLoad('errors', opsApi.errors);
  const code = STATUSES.find(x => x[0] === status)?.[1];
  const data = useLoad(`${type}|${status}`, () => opsApi.disasters(type === '전체' ? undefined : type, code));

  // 수동 재수집: 서버가 작업을 접수하면(202) 성공. 실제 수집 결과는 상태를 다시 받아 확인
  const recollect = (name: string) => confirm('다시 수집', `${name} 수집을 지금 요청할까요?`, '요청', async () => {
    setBusy(name);
    try {
      await opsApi.recollect(name);
      notify('수집을 요청했어요', '잠시 뒤 운영 상태에서 결과를 확인하세요.');
    } catch (e) {
      notify('수집 요청 실패', errText(e));
    } finally { setBusy(null); ops.reload(); }
  });

  return (
    <>
      <Group title="운영 상태">
        <LoadRow loading={ops.loading} error={ops.error} reload={ops.reload} />
        {ops.data && (
          <Row icon="server-outline" title="데이터 환경"
            right={<Badge text={ops.data.mockApiEnabled ? 'Mock API' : '실제 API'} color={ops.data.mockApiEnabled ? C.warning : C.success} />} />
        )}
        {ops.data?.apiStatuses.map(a => (
          <Row key={a.apiName} icon="cloud-download-outline" title={a.apiName}
            sub={`마지막 정상 수집 ${a.lastSuccessfulCollectedAt ? stamp(a.lastSuccessfulCollectedAt) : '기록 없음'}`}
            right={a.status === 'SUCCESS' ? apiBadge(C, a.status) : (
              <View style={{ alignItems: 'flex-end', gap: 6 }}>
                {apiBadge(C, a.status)}
                <SmallAction title="다시 수집" label={`${a.apiName} 다시 수집`} busy={busy === a.apiName} onPress={() => !busy && recollect(a.apiName)} />
              </View>
            )} />
        ))}
      </Group>

      <Group title="알림">
        <Row icon="megaphone-outline" title="운영 공지 보내기" sub="서버 기능 준비 중" onPress={() => router.push('/notice')} />
      </Group>

      <Group title="재난 데이터">
        <View style={{ paddingVertical: 10, gap: 6 }}>
          <View style={[s.row, { flexWrap: 'wrap' }]}>{(['전체', ...TYPES] as const).map(t => <Chip key={t} text={t} on={type === t} onPress={() => setType(t)} />)}</View>
          <View style={[s.row, { flexWrap: 'wrap' }]}>{STATUSES.map(([t]) => <Chip key={t} text={t} on={status === t} onPress={() => setStatus(t)} />)}</View>
        </View>
        <LoadRow loading={data.loading} error={data.error} reload={data.reload} />
        {data.data?.length === 0 && <Empty icon="search-outline" text="조건에 맞는 데이터가 없어요." />}
        {data.data?.map(d => (
          <Row key={d.id} icon={`${TYPE_ICON[d.type]}-outline` as IconName} title={`${d.type} · ${d.region}`}
            sub={`${d.level ?? '등급 없음'} · ${d.status} · 발생 ${d.occurredAt} · 갱신 ${d.updatedAt}`} onPress={() => router.push(`/disasters/${d.id}`)} />
        ))}
      </Group>

      <Group title="오류 로그">
        <LoadRow loading={errors.loading} error={errors.error} reload={errors.reload} />
        {errors.data?.errorLogs.length === 0 && <Empty icon="checkmark-done-outline" text="기록된 오류가 없어요." />}
        {/* 누르면 원인 전체를 펼쳐 봄 (접힌 상태는 한 줄) */}
        {errors.data?.errorLogs.map((e, i) => {
          const title = e.source ?? '출처 미상', cause = `${e.errorType ?? ''}${e.message ? ` · ${e.message}` : ''}`;
          return openErr === i ? (
            <Row key={i} icon="bug-outline" title={title} sub={`${stamp(e.occurredAt)}\n${cause}`} danger onPress={() => setOpenErr(null)} right={<Text style={s.small}>접기</Text>} />
          ) : (
            <Row key={i} icon="bug-outline" title={title} sub={`${stamp(e.occurredAt)} · ${cause.length > 24 ? `${cause.slice(0, 24)}…` : cause}`} danger onPress={() => setOpenErr(i)} />
          );
        })}
      </Group>
    </>
  );
}
