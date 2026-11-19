// 앱 공통 타입·표시용 상수 (백엔드 응답은 server.ts 가 이 형태로 바꿔 줌). 가짜 데이터는 두지 않음
export type Level = '경보' | '주의보' | '정보';
export type Status = '발생' | '진행중' | '종료' | '해제';
// FS-AISUM-001: 요약 처리상태. '갱신 대기' = 원문이 바뀌어 기존 요약이 최신이 아님
export type AiStatus = '완료' | '대기' | '실패' | '갱신 대기';
export type DisasterType = '지진' | '호우' | '산불';
export type Disaster = {
  id: string; type: DisasterType; title: string; region: string;
  regionKnown: boolean;       // 서버가 영향지역을 알려 줬는지. false 면 region 은 제목으로 대신한 값 (어디인지 단정할 수 없음)
  level?: Level;              // 공식 위험수준이 없으면 비워 둠 (임의 생성 금지)
  status: Status; occurredAt: string; updatedAt: string;
  stale?: boolean;            // FS-DATA-001: 최신 수집 실패 → 마지막 정상 데이터 표시 중 (상세에서만 앎)
  ai?: { status: AiStatus; text?: string; at?: string }; // 상세에서 따로 조회. 목록에는 없음
  sources: { org: string; text: string }[]; // 상세에서만 채워짐
};
export const isActive = (d: Pick<Disaster, 'status'>) => d.status === '발생' || d.status === '진행중';

export type Impact = '영향지역 포함' | '영향지역 밖' | '위치정보 미제공' | '판단 불가';
// updatedAt: 구성원 위치의 서버 갱신 시각(ms). 경과 시간은 보여 줄 때마다 지금 시각으로 계산
export type Member = { id: string; name: string; admin: boolean; sharing: boolean; lat?: number; lon?: number; updatedAt?: number; impact: Impact };
export type Group = { id: string; name: string; category: string; code: string; admin: boolean; members: Member[] };

// distance = 기준 위치에서 직선거리(km). type·capacity·updatedAt 은 상세 조회로 채움 (없으면 비워 둠)
export type Shelter = { id: string; name: string; addr: string; distance: number; lat: number; lon: number; type?: string; capacity?: number; updatedAt?: string; org?: string };
export const MAX_RADIUS = 5; // km, 백엔드 정책값 (SHELTER_MAX_RADIUS_KM)

export const WEATHER_ICON: Record<string, 'sunny' | 'cloudy' | 'rainy' | 'snow' | 'partly-sunny'> = {
  SUNNY: 'sunny', CLOUDY: 'partly-sunny', OVERCAST: 'cloudy', RAIN: 'rainy', SLEET: 'rainy', SNOW: 'snow',
};
export const LEVELS: Level[] = ['정보', '주의보', '경보']; // 낮음 → 높음
export const LEVEL_ICON = { 경보: 'alert-circle', 주의보: 'warning', 정보: 'information-circle' } as const;
export const TYPE_ICON = { 지진: 'pulse', 호우: 'rainy', 산불: 'flame' } as const;
export const TYPES: DisasterType[] = ['지진', '호우', '산불'];

export type Provider = 'Google' | 'Kakao';
// operator: 운영 관리 권한 (FS-OPS-000, 백엔드 OPS 역할). id: 백엔드 회원 ID
export type MemberInfo = { id: string; name: string; email: string; joinedAt: string; operator: boolean };

// 백엔드 그룹 분류 코드 ↔ 화면 이름 (백엔드는 FAMILY·FRIEND·OTHER 만 받음)
export const CATEGORIES = ['가족', '친구', '기타'];

// 관심지역: 서버 ID + 시·군·구 코드 + 표시 이름(별칭 가능, FS-AREA-003) + 지역 이름('서울 강남구', 재난 지역 비교용)
// regionKnown: region 을 이 기기가 실제로 확인했는지 (모르면 표시 이름을 대신 씀 — 별칭인지 판단할 수 없음)
export type Area = { id: string; code: string; name: string; region: string; regionKnown?: boolean };

// 색은 테마의 impactColor() 에서 정함
export const IMPACT_ICON = {
  '영향지역 포함': 'warning', '영향지역 밖': 'checkmark-circle', '위치정보 미제공': 'eye-off', '판단 불가': 'help-circle',
} as const satisfies Record<Impact, string>;
