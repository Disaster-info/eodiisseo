// FS-OPS: 운영 공지 발송 (운영자 전용) — 대상 지역·등급·제목·내용 → 확인 → 발송, 아래에 발송 이력
// ponytail: 발송은 백엔드 연계 전 목업 — 잠깐 기다린 뒤 이력에만 추가되고, 화면을 나가면 처음 이력으로 돌아감
import { useState } from 'react';
import { ActivityIndicator, Text, TextInput, View } from 'react-native';
import { Badge, Btn, Card, Chip, Header, Page, Segmented, confirm, levelFill, levelInk, notify, useTheme } from '../../components/ui';
import { LEVELS, Level, OPS_NOTICES, REGIONS } from '../../data/mock';
import { useApp } from '../../store/AppContext';
import { nowStamp } from './AdminSections';

export default function NoticeScreen() {
  const { C, s } = useTheme();
  const { member } = useApp();
  const [region, setRegion] = useState('전체');
  const [level, setLevel] = useState<Level>('정보');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [history, setHistory] = useState(OPS_NOTICES);
  if (!member?.operator) return null; // 라우터가 운영자에게만 열지만 한 번 더 막음

  const send = () => {
    if (title.trim().length < 2) return notify('제목을 2자 이상 입력해 주세요');
    if (body.trim().length < 5) return notify('내용을 5자 이상 입력해 주세요');
    confirm('공지 발송', `${region === '전체' ? '전체 사용자' : `${region} 사용자`}에게 [${level}] ${title.trim()} 공지를 보낼까요?\n보낸 공지는 취소할 수 없어요.`, '보내기', async () => {
      setSending(true);
      await new Promise(r => setTimeout(r, 1200));
      setHistory(h => [{ id: Date.now(), at: nowStamp(), region, level, title: title.trim(), body: body.trim() }, ...h]);
      setTitle(''); setBody(''); setSending(false);
      notify('공지를 보냈어요');
    });
  };

  return (
    <View style={s.fill}>
      <Header title="운영 공지 보내기" />
      <Page>
        <Text style={s.bold}>대상 지역</Text>
        <View style={[s.row, { flexWrap: 'wrap' }]}>
          {['전체', ...REGIONS].map(r => <Chip key={r} text={r} on={region === r} onPress={() => setRegion(r)} />)}
        </View>
        <Text style={s.bold}>등급</Text>
        <Segmented label="공지 등급" items={[...LEVELS]} value={level} onChange={setLevel} />
        <Text style={s.bold}>제목</Text>
        <TextInput accessibilityLabel="공지 제목" style={[s.input, { marginVertical: 0 }]} value={title} onChangeText={setTitle} maxLength={40}
          placeholder="예: 서비스 점검 안내" placeholderTextColor={C.sub} />
        <Text style={s.bold}>내용</Text>
        <TextInput accessibilityLabel="공지 내용" style={[s.input, { marginVertical: 0, minHeight: 120, textAlignVertical: 'top' }]} value={body} onChangeText={setBody}
          maxLength={300} multiline placeholder="사용자에게 보낼 내용" placeholderTextColor={C.sub} />
        <Text style={[s.small, { textAlign: 'right' }]}>{body.length}/300</Text>
        {sending ? <ActivityIndicator color={C.primary} accessibilityLabel="공지 보내는 중" /> : <Btn title="공지 보내기" icon="megaphone-outline" onPress={send} />}

        <Text style={s.section}>발송 이력</Text>
        {history.map(n => (
          <Card key={n.id}>
            <View style={[s.row, { gap: 6 }]}>
              <Badge solid text={n.level} color={levelFill(C, n.level)} fg={levelInk(C, n.level)} />
              <Text style={[s.bold, { flex: 1 }]}>{n.title}</Text>
            </View>
            <Text style={s.muted}>{n.at} · {n.region}</Text>
            <Text style={[s.body, { marginTop: 4 }]}>{n.body}</Text>
          </Card>
        ))}
      </Page>
    </View>
  );
}
