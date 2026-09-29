// 목업 데이터 — 백엔드 API 연동 전까지 화면 확인용
export type Level = '경보' | '주의보' | '정보';
export type Status = '발생' | '진행중' | '종료' | '해제';
// FS-AISUM-001: 요약 처리상태. '갱신 대기' = 원문이 바뀌어 기존 요약이 최신이 아님
export type AiStatus = '완료' | '대기' | '실패' | '갱신 대기';
export type Disaster = {
  id: number; type: '지진' | '호우' | '산불'; region: string;
  level?: Level;              // 공식 위험수준이 없으면 비워 둠 (임의 생성 금지)
  status: Status; occurredAt: string; updatedAt: string;
  stale?: boolean;            // FS-DATA-001: 최신 수집 실패 → 마지막 정상 데이터 표시 중
  ai: { status: AiStatus; text?: string; at?: string };
  sources: { org: string; text: string }[]; // FS-DATA-003: 동일 재난 통합 시 기관별 원문 유지
};
export type Impact = '영향지역 포함' | '영향지역 밖' | '위치정보 미제공' | '판단 불가';
export type Member = { id: number; name: string; admin: boolean; sharing: boolean; place?: string; updatedMin?: number; impact: Impact };
export type Group = { id: number; name: string; category: string; code: string; createdByMe: boolean; members: Member[] };

export const DISASTERS: Disaster[] = [
  { id: 1, type: '호우', region: '서울 강남구', level: '경보', status: '진행중', occurredAt: '09-28 06:10', updatedAt: '09-28 13:40',
    ai: { status: '완료', at: '09-28 13:41', text: '강남구 일대 시간당 50mm 이상 강한 비. 저지대·지하공간 출입을 피하세요.' },
    sources: [
      { org: '기상청', text: '[기상청] 09월 28일 06시 10분 서울 강남구 호우경보 발표. 산사태·상습침수 등 위험지역 대피, 외출자제 등 안전에 주의 바랍니다.' },
      { org: '행정안전부', text: '[행정안전부] 강남구 호우경보. 지하차도·하천변 접근 금지, 저지대 주민은 대피 준비 바랍니다.' },
    ] },
  { id: 2, type: '산불', region: '강원 강릉시', level: '주의보', status: '진행중', occurredAt: '09-28 11:02', updatedAt: '09-28 13:15', stale: true,
    ai: { status: '대기' },
    sources: [{ org: '산림청', text: '[산림청] 강릉시 옥계면 일원 산불 발생. 인근 주민은 마을회관 등 안전한 곳으로 대피하시기 바랍니다.' }] },
  { id: 3, type: '지진', region: '경북 경주시', level: '정보', status: '해제', occurredAt: '09-27 22:31', updatedAt: '09-27 23:00',
    ai: { status: '완료', at: '09-27 22:35', text: '경주시 남남서쪽 규모 3.1 지진. 피해 보고 없음.' },
    sources: [{ org: '기상청', text: '[기상청] 09-27 22:31 경북 경주시 남남서쪽 9km 지역 규모 3.1 지진 발생. 낙하물로부터 몸을 보호하시기 바랍니다.' }] },
  { id: 4, type: '호우', region: '경기 수원시', level: '주의보', status: '진행중', occurredAt: '09-28 08:00', updatedAt: '09-28 12:50',
    ai: { status: '갱신 대기', at: '09-28 08:02', text: '수원시 호우주의보. 하천변 산책로 이용을 자제하세요.' },
    sources: [{ org: '기상청', text: '[기상청] 09월 28일 12시 50분 경기 수원시 호우주의보 유지. 하천변·계곡 출입을 자제하고 배수 상태를 점검하시기 바랍니다.' }] },
  { id: 5, type: '지진', region: '부산 해운대구', status: '종료', occurredAt: '09-20 03:12', updatedAt: '09-20 03:40',
    ai: { status: '실패' },
    sources: [{ org: '기상청', text: '[기상청] 09-20 03:12 부산 해운대구 동남동쪽 해역 규모 2.4 지진. 피해 없을 것으로 예상됩니다.' }] },
];
export const isActive = (d: Disaster) => d.status === '발생' || d.status === '진행중';

// FS-SHELTER: distance = 지역 기준점으로부터 직선거리(km). 없는 항목(capacity 등)은 비워 둠
export type Shelter = { id: number; region: string; name: string; addr: string; distance: number; type: string; capacity?: number; updatedAt?: string };
export const SHELTERS: Shelter[] = [
  { id: 1, region: '서울 강남구', name: '역삼초등학교 체육관', addr: '서울 강남구 역삼로 123', distance: 0.4, capacity: 500, type: '지진옥외대피장소', updatedAt: '2026-09-01' },
  { id: 2, region: '서울 강남구', name: '강남구민회관', addr: '서울 강남구 학동로 426', distance: 1.2, capacity: 800, type: '임시주거시설', updatedAt: '2026-09-01' },
  { id: 3, region: '서울 강남구', name: '선릉공원', addr: '서울 강남구 선릉로 100길', distance: 1.8, type: '지진옥외대피장소' },
  { id: 4, region: '경기 수원시', name: '수원종합운동장', addr: '경기 수원시 장안구 경수대로 893', distance: 2.6, capacity: 3000, type: '지진옥외대피장소', updatedAt: '2026-07-10' },
  { id: 5, region: '서울 마포구', name: '망원한강공원', addr: '서울 마포구 마포나루길 467', distance: 0.9, type: '지진옥외대피장소', updatedAt: '2026-08-15' },
];
export const MAX_RADIUS = 5; // km, 시스템 정책값
// from km 부터 시작해 대피소가 나오는 첫 반경에서 멈춤. 최대 반경까지 없으면 빈 목록
export function searchShelters(all: Shelter[], region: string, from = 1) {
  const inRegion = all.filter(x => x.region === region);
  for (let r = from; r <= MAX_RADIUS; r++) {
    const hit = inRegion.filter(x => x.distance <= r);
    if (hit.length) return { radius: r, list: hit.sort((a, b) => a.distance - b.distance) };
  }
  return { radius: MAX_RADIUS, list: [] };
}

// 경로 API 응답 흉내. 없는 대피소 id = 경로를 찾을 수 없음. minutes 는 API 가 준 경우에만
export const ROUTES: Record<number, { km: number; minutes?: number }> = {
  1: { km: 0.6, minutes: 9 }, 2: { km: 1.5, minutes: 22 }, 3: { km: 2.3 }, 5: { km: 1.1, minutes: 16 },
};

type Weather = { temp: number; cond: '맑음' | '흐림' | '비' | '눈'; at: string };
// '서울 마포구' 는 일부러 없음 → 날씨 조회 실패 화면 확인용
export const WEATHER: Record<string, Weather> = {
  '서울 강남구': { temp: 21, cond: '비', at: '13:30' },
  '경기 수원시': { temp: 22, cond: '흐림', at: '13:30' },
  '부산 해운대구': { temp: 25, cond: '맑음', at: '13:30' },
  '강원 강릉시': { temp: 18, cond: '맑음', at: '13:30' },
  '경북 경주시': { temp: 23, cond: '흐림', at: '13:30' },
};
export const WEATHER_ICON = { 맑음: 'sunny', 흐림: 'cloudy', 비: 'rainy', 눈: 'snow' } as const;
// FS-WEATHER-001: 정의된 상태만 안내문 표시 (흐림은 정의 없음)
export const WEATHER_TIP: Partial<Record<Weather['cond'], string>> = {
  비: '우산을 챙기고 지하차도·하천변은 피하세요.', 눈: '빙판길 미끄럼에 주의하세요.', 맑음: '야외 활동 시 수분을 충분히 섭취하세요.',
};
export const LEVELS: Level[] = ['정보', '주의보', '경보']; // 낮음 → 높음
export const LEVEL_COLOR: Record<Level, string> = { 경보: '#E5484D', 주의보: '#F59E0B', 정보: '#6B7280' };
export const LEVEL_ICON = { 경보: 'alert-circle', 주의보: 'warning', 정보: 'information-circle' } as const;
export const TYPE_ICON = { 지진: 'pulse', 호우: 'rainy', 산불: 'flame' } as const;
export const REGIONS = ['서울 강남구', '서울 마포구', '경기 수원시', '부산 해운대구', '강원 강릉시', '경북 경주시'];

// FS-NOTI-002: 발송 이력. disasterId 99 = 삭제된 재난 (잘못된 화면 이동 방지 확인용)
export const NOTI_HISTORY = [
  { id: 1, disasterId: 1, at: '09-28 06:11', title: '[호우 경보] 서울 강남구', ok: true },
  { id: 2, disasterId: 4, at: '09-28 08:01', title: '[호우 주의보] 경기 수원시', ok: true },
  { id: 3, disasterId: 99, at: '09-25 14:20', title: '[호우 주의보] 서울 강남구', ok: false },
];

// FS-CHAT-001: 공식 행동요령 자료 (실제로는 검색 대상 DB)
export const GUIDES = [
  { keys: ['지진', '흔들'], org: '행정안전부', title: '지진 발생 시 행동요령',
    text: '흔들리는 동안 탁자 아래로 들어가 몸을 보호하고, 흔들림이 멈추면 계단으로 넓은 공간에 대피하세요. 엘리베이터는 이용하지 마세요.' },
  { keys: ['호우', '침수', '비', '홍수'], org: '행정안전부', title: '호우 시 행동요령',
    text: '지하공간·지하차도·하천변에 접근하지 말고, 침수 우려 시 높은 곳으로 대피하세요. 전기 차단기를 내리고 가스 밸브를 잠그세요.' },
  { keys: ['산불', '연기', '불'], org: '산림청', title: '산불 발생 시 행동요령',
    text: '바람 방향을 확인해 산불 반대 방향의 낮은 곳으로 대피하세요. 젖은 수건으로 입과 코를 막고 마을회관 등 안전한 장소로 이동하세요.' },
];
export const BASIC_GUIDE = '침착하게 공식 재난문자와 방송 안내를 따르고, 위급하면 119에 신고하세요. 가까운 대피소 위치를 미리 확인해 두세요.';

// FS-OPS-001·002: 운영 상태 (ok: null = 상태 확인 불가)
export const OPS_MOCK_API: boolean | null = true;
export const OPS_APIS: { name: string; ok: boolean | null; lastOk?: string; failAt?: string }[] = [
  { name: '기상청 특보·지진', ok: true, lastOk: '09-28 13:40' },
  { name: '산림청 산불', ok: false, lastOk: '09-28 13:15', failAt: '09-28 13:45' },
  { name: '행정안전부 대피소', ok: null },
];
export const OPS_ERRORS = [
  { at: '09-28 13:45', target: '산림청 산불 API', cause: 'HTTP 503 Service Unavailable (serviceKey=****)' },
  { at: '09-28 12:02', target: 'AI 요약 (재난 #2)', cause: '응답 시간 초과' },
  { at: '09-27 09:30', target: '기상청 특보 API', cause: '원인 확인 불가' },
];

export const CHECKLIST: Record<string, string[]> = {
  지진: ['비상용 손전등 준비', '가구 고정 확인', '대피 장소 확인', '비상 식수 3일분'],
  호우: ['배수구 점검', '비상 연락망 정리', '침수 위험지역 확인', '우비·장화 준비'],
  산불: ['젖은 수건 준비', '대피 경로 2곳 확인', '마스크 준비', '차량 연료 확인'],
};

export type Provider = 'Google' | 'Kakao';
// operator: 운영 관리 권한 (FS-OPS-000). 시연용으로 true
export type MemberInfo = { name: string; email: string; joinedAt: string; operator: boolean };
export const MOCK_MEMBER: MemberInfo = { name: '홍길동', email: 'hong@example.com', joinedAt: '2026-09-01', operator: true };

// 소셜 인증 흉내. 실제로는 Google/Kakao SDK 인증 → 백엔드 토큰 검증으로 교체
export const mockAuthenticate = (_p: Provider) => new Promise<MemberInfo>(r => setTimeout(() => r(MOCK_MEMBER), 800));

export const CATEGORIES = ['가족', '친구', '직장', '기타'];
export const ME = 0; // 로그인한 본인의 구성원 id
export const INIT_GROUPS: Group[] = [
  { id: 1, name: '우리 가족', category: '가족', code: 'FAM-7K2Q', createdByMe: true, members: [
    { id: ME, name: '나', admin: true, sharing: true, place: '서울 강남구', updatedMin: 1, impact: '영향지역 포함' },
    { id: 1, name: '엄마', admin: false, sharing: true, place: '경기 수원시', updatedMin: 4, impact: '영향지역 포함' },
    { id: 2, name: '아빠', admin: false, sharing: true, place: '부산 해운대구', updatedMin: 95, impact: '영향지역 밖' },
    { id: 3, name: '동생', admin: false, sharing: false, impact: '위치정보 미제공' },
  ] },
];
// 초대 코드로 참여해 볼 수 있는 가짜 "서버" 그룹 (FULL-0000 은 10명 꽉 찬 그룹)
export const JOINABLE_GROUPS: Group[] = [
  { id: 100, name: '대학 동기', category: '친구', code: 'FRD-1234', createdByMe: false, members: [
    { id: 11, name: '민수', admin: true, sharing: true, place: '서울 마포구', updatedMin: 3, impact: '영향지역 밖' },
    { id: 12, name: '지영', admin: false, sharing: false, impact: '위치정보 미제공' },
  ] },
  { id: 101, name: '동아리', category: '친구', code: 'FULL-0000', createdByMe: false,
    members: Array.from({ length: 10 }, (_, i) => ({ id: 200 + i, name: `회원${i + 1}`, admin: i === 0, sharing: false, impact: '위치정보 미제공' as Impact })) },
];
export const IMPACT_STYLE = {
  '영향지역 포함': { color: '#E5484D', icon: 'warning' }, '영향지역 밖': { color: '#16A34A', icon: 'checkmark-circle' },
  '위치정보 미제공': { color: '#6B7280', icon: 'eye-off' }, '판단 불가': { color: '#6B7280', icon: 'help-circle' },
} as const satisfies Record<Impact, { color: string; icon: string }>;
