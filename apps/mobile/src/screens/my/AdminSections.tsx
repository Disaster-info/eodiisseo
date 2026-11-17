// FS-OPS: 관리자 설정 탭 메인의 운영 현황 (운영자 전용)
// 운영 상태(+수동 재수집) → 이용 통계 → 알림 발송(+재발송·공지) → 대피소 데이터 점검 → 재난 데이터 → 오류 로그
// ponytail: 재수집·재발송·통계는 백엔드 연계 전 목업 — 버튼은 잠깐 기다린 뒤 성공으로 바뀌고, 화면을 다시 열면 처음 값. 관리자 API 가 생기면 교체
import { router } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import { Badge, Chip, Empty, IconName, confirm, notify, useTheme, type Colors } from '../../components/ui';
import { DISASTERS, OPS_APIS, OPS_ERRORS, OPS_MOCK_API, OPS_PUSH, OPS_PUSH_FAILS, OPS_STATS, SHELTERS, TYPE_ICON } from '../../data/mock';
import { Group, Row } from './rows';

const TYPES = ['전체', '지진', '호우', '산불'];
const STATUSES = ['전체', '발생', '진행중', '종료', '해제'];
const ERROR_KINDS = ['전체', 'API', 'AI 요약'] as const;
const errorKind = (target: string) => (target.startsWith('AI') ? 'AI 요약' : 'API');

/** 지금 시각을 목업 데이터와 같은 'MM-DD HH:mm' 형식으로 */
export function nowStamp() {
  const d = new Date(), p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
const fakeRequest = () => new Promise(r => setTimeout(r, 1200)); // 목업: 서버 요청 흉내

// ok: null(확인 불가)을 정상으로 표시하지 않음
const apiBadge = (C: Colors, ok: boolean | null) =>
  ok === null ? <Badge text="확인 불가" color={C.sub} /> : ok ? <Badge text="정상" icon="checkmark-circle" color={C.success} /> : <Badge text="실패" icon="close-circle" color={C.danger} />;

// 행 오른쪽의 작은 동작 버튼 (다시 수집·재발송). busy 면 돌아가는 표시
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

export function AdminSections() {
  const { C, s } = useTheme();
  const [apis, setApis] = useState(OPS_APIS);
  const [fails, setFails] = useState(OPS_PUSH_FAILS);
  const [busy, setBusy] = useState<string | null>(null); // 진행 중인 동작 (한 번에 하나)
  const [type, setType] = useState('전체');
  const [status, setStatus] = useState('전체');
  const [errKind, setErrKind] = useState<(typeof ERROR_KINDS)[number]>('전체');
  const [openErr, setOpenErr] = useState<number | null>(null); // 펼쳐 본 오류 로그

  // 1. API 수동 재수집
  const recollect = async (name: string) => {
    setBusy(name);
    await fakeRequest();
    setApis(as => as.map(a => (a.name === name ? { ...a, ok: true, lastOk: nowStamp(), failAt: undefined } : a)));
    setBusy(null);
  };
  // 3. 실패한 알림 재발송
  const resend = (f: (typeof OPS_PUSH_FAILS)[number]) => confirm('알림 재발송', `${f.title}\n받지 못한 ${f.count}명에게 다시 보낼까요?`, '재발송', async () => {
    setBusy(`push-${f.id}`);
    await fakeRequest();
    setFails(fs => fs.filter(x => x.id !== f.id));
    setBusy(null);
    notify('재발송 완료', `${f.count}명에게 다시 보냈어요.`);
  });

  const failCount = OPS_PUSH.sent - OPS_PUSH.ok;
  // 5. 대피소 데이터 점검: 원본에 비어 있는 항목 (지어내지 않고 '누락'으로만 표시)
  const shelterIssues = SHELTERS.map(sh => ({ sh, missing: [sh.capacity === undefined && '수용 인원', !sh.updatedAt && '갱신일'].filter(Boolean) as string[] }))
    .filter(x => x.missing.length);
  const data = DISASTERS.filter(d => (type === '전체' || d.type === type) && (status === '전체' || d.status === status));
  const errors = OPS_ERRORS.map((e, i) => ({ ...e, i })).filter(e => errKind === '전체' || errorKind(e.target) === errKind);

  return (
    <>
      <Group title="운영 상태">
        <Row icon="server-outline" title="데이터 환경"
          right={OPS_MOCK_API === null ? <Badge text="확인 불가" color={C.sub} /> : <Badge text={OPS_MOCK_API ? 'Mock API' : '실제 API'} color={OPS_MOCK_API ? C.warning : C.success} />} />
        {apis.map(a => (
          <Row key={a.name} icon="cloud-download-outline" title={a.name}
            sub={`마지막 정상 수집 ${a.lastOk ?? '기록 없음'}${a.failAt ? ` · 최근 실패 ${a.failAt}` : ''}`}
            right={a.ok ? apiBadge(C, a.ok) : (
              <View style={{ alignItems: 'flex-end', gap: 6 }}>
                {apiBadge(C, a.ok)}
                <SmallAction title="다시 수집" label={`${a.name} 다시 수집`} busy={busy === a.name} onPress={() => !busy && recollect(a.name)} />
              </View>
            )} />
        ))}
      </Group>

      <Group title="이용 통계">
        <Row icon="people-outline" title="가입 회원" value={`${OPS_STATS.members.toLocaleString()}명`} />
        <Row icon="pulse-outline" title="최근 7일 이용" value={`${OPS_STATS.active7d.toLocaleString()}명`} />
        <Row icon="people-circle-outline" title="안심그룹" value={`${OPS_STATS.groups.toLocaleString()}개`} />
        <Row icon="location-outline" title="위치 공유 중인 회원" sub="개인 위치는 보여주지 않고 비율만" value={`${OPS_STATS.sharingRate}%`} />
      </Group>

      <Group title="알림 발송 (최근 7일)">
        <Row icon="megaphone-outline" title="운영 공지 보내기" sub="지역·등급을 골라 전체 사용자에게" onPress={() => router.push('/notice')} />
        <Row icon="send-outline" title="재난 알림 발송" value={`${OPS_PUSH.sent.toLocaleString()}건`} />
        <Row icon="checkmark-circle-outline" title="성공" value={`${OPS_PUSH.ok.toLocaleString()}건 (${Math.round((OPS_PUSH.ok / OPS_PUSH.sent) * 100)}%)`} />
        {failCount > 0 && <Row icon="close-circle-outline" title="실패" value={`${failCount}건`} danger />}
        {fails.map(f => (
          <Row key={f.id} icon="refresh-outline" title={f.title} sub={`${f.at} · ${f.count}명 실패 · ${f.cause}`}
            right={<SmallAction title="재발송" label={`${f.title} 재발송`} busy={busy === `push-${f.id}`} onPress={() => !busy && resend(f)} />} />
        ))}
      </Group>

      <Group title="대피소 데이터 점검">
        <Row icon={shelterIssues.length ? 'alert-circle-outline' : 'checkmark-done-outline'} title="등록된 대피소"
          value={shelterIssues.length ? `${SHELTERS.length}곳 중 ${shelterIssues.length}곳 확인 필요` : `${SHELTERS.length}곳 모두 정상`} />
        {shelterIssues.map(({ sh, missing }) => (
          <Row key={sh.id} icon="shield-outline" title={sh.name} sub={`${sh.region} · ${sh.addr} · ${missing.join('·')} 누락`} />
        ))}
      </Group>

      <Group title="재난 데이터">
        <View style={{ paddingVertical: 10, gap: 6 }}>
          <View style={[s.row, { flexWrap: 'wrap' }]}>{TYPES.map(t => <Chip key={t} text={t} on={type === t} onPress={() => setType(t)} />)}</View>
          <View style={[s.row, { flexWrap: 'wrap' }]}>{STATUSES.map(t => <Chip key={t} text={t} on={status === t} onPress={() => setStatus(t)} />)}</View>
        </View>
        {data.length === 0 && <Empty icon="search-outline" text="조건에 맞는 데이터가 없어요." />}
        {data.map(d => (
          <Row key={d.id} icon={`${TYPE_ICON[d.type]}-outline` as IconName} title={`#${d.id} ${d.type} · ${d.region}`}
            sub={`${d.level ?? '등급 없음'} · ${d.status} · 발생 ${d.occurredAt} · 갱신 ${d.updatedAt} · AI ${d.ai.status}`} />
        ))}
      </Group>

      <Group title="오류 로그">
        <View style={[s.row, { flexWrap: 'wrap', paddingVertical: 10 }]}>
          {ERROR_KINDS.map(k => <Chip key={k} text={k} on={errKind === k} onPress={() => { setErrKind(k); setOpenErr(null); }} />)}
        </View>
        {errors.length === 0 && <Empty icon="checkmark-done-outline" text="기록된 오류가 없어요." />}
        {/* 누르면 원인 전체 + 분류를 펼쳐 봄 (접힌 상태는 한 줄) */}
        {errors.map(e => openErr === e.i ? (
          <Row key={e.i} icon="bug-outline" title={e.target} sub={`${e.at} · ${errorKind(e.target)}\n${e.cause}`} danger onPress={() => setOpenErr(null)}
            right={<Text style={s.small}>접기</Text>} />
        ) : (
          <Row key={e.i} icon="bug-outline" title={e.target} sub={`${e.at} · ${e.cause.length > 24 ? `${e.cause.slice(0, 24)}…` : e.cause}`} danger onPress={() => setOpenErr(e.i)} />
        ))}
      </Group>
    </>
  );
}
