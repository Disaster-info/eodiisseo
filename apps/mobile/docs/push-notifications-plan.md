# 푸시 알림 도입 계획 (어디있어)

> 기준: Expo SDK 58 (`expo-notifications` 권장 버전 ~58.1.8), 2026-11-10 작성
> 범위: iOS·Android 앱 푸시. 웹은 6장 참고

## 0. 한눈에 보기

| 항목 | 정한 방향 |
|---|---|
| 발송 경로 | 백엔드 → **Expo 푸시 서비스** → FCM(안드로이드)·APNs(iOS) → 휴대폰 |
| 앱 모듈 | `expo-notifications` (Expo Go 에서는 원격 푸시 불가 → **개발 빌드 필수**) |
| 알림 종류 | ① 재난(경보·주의보·정보·해제) ② 안심그룹(가족 영향지역) ③ 운영 공지 |
| 원칙 | 정부 재난문자(긴급재난문자, CBS)를 **대신하지 않는 보조 수단**. 빠르게·정확하게·과하지 않게 |

이미 있는 것: 알림 설정 화면(`src/screens/my/NotiScreen.tsx` — 켜기/관심지역/최소 심각도/야간), 알림 내역 화면(목업), 관리자 화면의 발송 현황·재발송(목업).
없는 것: 실제 푸시 모듈, 기기 토큰, 서버 발송, 설정 저장(지금은 앱 메모리에만 있음).

---

## 1. 전체 흐름

```
[공공 API 수집] → 백엔드: 새 재난/등급 변경 감지
                    ↓ 대상 계산 (기준 위치·관심지역·최소 심각도·야간 설정)
                    ↓ 같은 재난 중복 방지 (재난 ID + 등급 + 상태)
                 Expo 푸시 서비스 (최대 100건씩 묶어 보냄)
                    ↓
               FCM / APNs → 휴대폰 알림
                    ↓ 사용자가 누름
               앱: data.url 로 해당 화면 열기 (/disasters/1, /groups/1 …)
```

---

## 2. 알림 종류와 강도

알림을 받는 쪽이 놀라지 않게, **위험할수록 강하게** 울리고 나머지는 조용히.

| 종류 | 예시 문구 | 안드로이드 채널 (중요도) | iOS 수준 | 누르면 |
|---|---|---|---|---|
| 경보 (현재 위치·관심지역) | `[호우경보] 서울 강남구 — 저지대·지하공간 출입을 피하세요` | `disaster-alert` (MAX, 잠금화면 전체 공개) | `time-sensitive` | 재난 상세 |
| 주의보 | `[호우주의보] 경기 수원시` | `disaster-warning` (HIGH) | `active` | 재난 상세 |
| 정보·해제 | `[해제] 서울 강남구 호우경보가 해제됐어요` | `disaster-info` (DEFAULT) | `passive` | 재난 상세 |
| 안심그룹 | `엄마가 호우주의보 영향지역에 있어요` | `family` (HIGH) | `active` | 그룹 정보 |
| 운영 공지 | `서비스 점검 안내` | `notice` (LOW) | `passive` | 공지(또는 홈) |

- 안드로이드 채널은 **한 번 만들면 중요도·소리를 바꿀 수 없음**(이름·설명만 변경 가능) → 출시 전에 위 표를 확정. 바꾸려면 새 채널 ID 필요
- 서버가 보낸 `channelId` 가 기기에 없으면 **알림이 표시되지 않음** → 앱이 시작할 때 채널을 모두 먼저 만들어 둠
- iOS '중요 알림(Critical Alerts, 무음 모드에서도 소리)'은 **애플의 별도 승인(entitlement)** 이 필요 → 출시 후 선택 과제(8장)

---

## 3. 앱(프론트) 작업

### 1단계 — 백엔드 없이 지금 할 수 있는 것

1. **설치·설정**
   - `npx expo install expo-notifications expo-constants`
   - `app.json` 플러그인: `["expo-notifications", { "icon": "./assets/notification-icon.png", "color": "#0B4A6B", "defaultChannel": "disaster-info" }]`
   - 안드로이드 상태바 아이콘: **96×96, 흰색 + 투명 배경** PNG 필요 → 지금 있는 `android-icon-monochrome.png`(432×432 흰색 실루엣)를 96×96 으로 줄여 `notification-icon.png` 로 추가
2. **채널 만들기** (앱 시작 시, 안드로이드만): 2장의 5개 채널. 안드로이드 13+ 는 채널이 하나 이상 있어야 권한 창이 뜨고, **토큰 발급 전에 채널을 만들어야 함**
3. **권한 요청 시점** — 앱을 처음 열자마자 묻지 않음
   - 로그인 → 위치 동의 다음에 "재난 알림을 받으시겠어요?" 안내 화면(왜 필요한지 설명) → 그다음 시스템 권한 창
   - 거부하면: 내정보 › 재난 알림 화면에 "휴대폰 설정에서 알림이 꺼져 있어요 · 설정 열기" 안내
   - iOS 는 `getPermissionsAsync()` 의 `ios.status` 로 판단 (최상위 `status` 만 보면 부정확)
4. **토큰 발급**: `getExpoPushTokenAsync({ projectId })` — `projectId` 는 EAS 프로젝트 ID(`Constants.expoConfig.extra.eas.projectId`). 오프라인이면 실패하므로 재시도. `addPushTokenListener` 로 토큰이 바뀌면 다시 등록
5. **앱이 켜져 있을 때 받은 알림**: `setNotificationHandler` 로 경보·주의보는 배너+소리, 나머지는 목록에만 (`shouldShowAlert` 는 지원 중단 → `shouldShowBanner`·`shouldShowList` 사용)
6. **알림을 눌렀을 때 화면 이동**: 루트 `src/app/_layout.tsx` 에서
   - 앱이 꺼진 상태에서 눌러 열린 경우: `getLastNotificationResponseAsync()` (동기 함수는 지원 중단)
   - 켜진 상태: `addNotificationResponseReceivedListener`
   - 알림 `data.url`(예: `/disasters/1`)을 `router.push`. **로그인 전이면 로그인 후 이동**, 지워진 재난이면 지금 알림 내역처럼 "재난 정보를 찾을 수 없어요" 안내
7. **화면 확인**: 로컬 알림(`scheduleNotificationAsync`)으로 채널·소리·화면 이동을 먼저 시험 (Expo Go 에서도 로컬 알림은 됨)

### 2단계 — 백엔드 API 가 생기면

| 할 일 | API (제안) |
|---|---|
| 토큰 등록·갱신 (로그인 후, 토큰 변경 시) | `PUT /api/v1/members/me/push-tokens` `{ token, platform, appVersion }` |
| 토큰 해제 (로그아웃·탈퇴) | `DELETE /api/v1/members/me/push-tokens/{token}` |
| 알림 설정 저장·불러오기 (지금 `NotiScreen` 값) | `GET/PUT /api/v1/members/me/notification-settings` |
| 알림 내역 (지금 `NOTI_HISTORY` 목업) | `GET /api/v1/members/me/notifications` |

### 3단계 — 운영
- 관리자 화면의 발송 현황·실패·재발송(지금 `OPS_PUSH` 목업)을 실제 집계 API 로 교체

---

## 4. 백엔드 작업 (백엔드 팀 전달용)

1. **토큰 저장**: 회원 1명이 기기 여러 대 가능. `(회원, 토큰, 플랫폼, 활성 여부, 마지막 갱신)` 
2. **대상 계산**: 재난 지역이 회원의 기준 위치 또는 '알림 받을 관심지역'에 포함 + 최소 심각도 이상 + 야간 설정 확인
3. **중복 방지**: 같은 재난의 같은 등급·상태는 한 번만. 등급이 오르면(주의보 → 경보) 다시 보냄. `collapseId`(iOS)·`tag`(안드로이드)를 재난 ID 로 주면 기기에서도 이전 알림을 덮어씀
4. **발송**: `POST https://exp.host/--/api/v2/push/send`
   - 한 요청에 **최대 100건**, 프로젝트당 **초당 600건** 한도
   - 429·5xx 는 점점 간격을 늘려 재시도, 형식 오류(4xx)는 재시도하지 않음
   - 본문(`data` 포함) **4KB 이하**. `ttl` 은 재난 알림이면 짧게(예: 1시간 — 늦게 도착한 경보는 오히려 혼란)
5. **결과 확인**: 보낸 뒤 약 15분 후 `POST .../push/getReceipts` (최대 1000개씩, 24시간 뒤 삭제됨)
   - `DeviceNotRegistered` → 그 토큰은 **즉시 비활성화** (앱 삭제·로그아웃)
   - `ticket 의 status: ok` 는 "Expo 가 받았다"는 뜻일 뿐, 사용자가 받았다는 뜻이 아님
6. **보안**: Expo 대시보드에서 '강화된 푸시 보안'을 켜고 액세스 토큰을 `Authorization: Bearer …` 로. 이 토큰은 **서버에만**(유출되면 남이 우리 앱으로 알림을 보낼 수 있음)
7. **개인정보**: 그룹 알림은 위치 공유에 동의한 구성원만, 알림 문구에 정확한 주소·좌표를 넣지 않음

---

## 5. 준비물 (계정·인증서)

| 준비물 | 어디서 | 비고 |
|---|---|---|
| EAS 프로젝트 | `npx eas-cli@latest init` | `projectId` 가 app 설정에 들어감 (토큰 발급에 필요) |
| Firebase 프로젝트 + `google-services.json` | Firebase 콘솔 | app 설정 `android.googleServicesFile` 에 경로 지정 |
| FCM V1 서비스 계정 키(JSON) | Firebase › 설정 › 서비스 계정 › 새 비공개 키 | `eas credentials` 로 EAS 에 업로드. **git 에 올리지 않기** |
| Apple Developer 유료 계정 | developer.apple.com | APNs 키는 `eas build` 할 때 "Setup Push Notifications" 에 '예' 로 자동 생성 |
| 테스트 기기 | 실제 휴대폰 | iOS 테스트 기기는 첫 `eas build` 전에 등록 |

---

## 6. 웹

`expo-notifications` 는 **iOS·안드로이드 전용**이라 웹에는 푸시를 보낼 수 없습니다.
- 지금: 웹은 화면 안에서만 최신 정보 표시 (새로고침·주기적 갱신)
- 나중(선택): 브라우저 웹 푸시(Web Push + 서비스 워커 + VAPID 키)를 별도로 구현. iPhone Safari 는 '홈 화면에 추가'한 경우에만 웹 푸시 가능

---

## 7. 시험 방법

1. 개발 빌드 만들기: `npx eas-cli@latest build --profile development` (안드로이드 Expo Go 는 SDK 53부터 원격 푸시 불가)
2. 로컬 알림으로 채널·소리·화면 이동 확인
3. Expo 푸시 테스트 도구(expo.dev/notifications)에 토큰을 넣어 실제 푸시 확인
4. 경우별 확인: 앱 켜짐 / 백그라운드 / 완전히 꺼짐 · 로그아웃 상태에서 누름 · 지워진 재난 · 방해금지 모드 · 권한 거부 · 다크 모드

---

## 8. 정해야 할 것

| 질문 | 제안 |
|---|---|
| **야간 알림을 꺼도 경보는 보낼까?** 지금 설정 문구는 "끄면 22시 이후에는 알림을 보내지 않아요" | **경보는 야간에도 보냄**(생명과 직결). 문구를 "주의보·정보는 22시 이후 보내지 않아요. 경보는 항상 알려요" 로 수정 |
| 해제 알림을 보낼까? | 경보·주의보를 받았던 사람에게만 보냄 |
| 하루 최대 알림 수 | 정보·공지는 하루 3건 이하로 묶음, 경보·주의보는 제한 없음 |
| iOS 중요 알림(무음에서도 울림) 신청 | 1차 출시는 `time-sensitive` 로 시작, 반응을 보고 애플에 신청 |
| Expo 푸시 서비스 vs FCM·APNs 직접 | Expo 로 시작 (구현이 가장 간단). Expo 푸시 서비스는 **SLA(가용성 보장)가 없으므로**, 사용자가 늘면 FCM·APNs 직접 발송으로 바꿀 수 있게 서버의 발송 부분을 한 곳에 모아 둠 |

## 9. 일정 (제안)

| 단계 | 내용 | 필요한 것 |
|---|---|---|
| 1단계 | 모듈 설치, 채널, 권한 안내 화면, 토큰 발급, 알림 탭 이동, 로컬 알림 시험 | 프론트만 (개발 빌드) |
| 2단계 | 토큰 등록·설정 저장·알림 내역 API 연결 | 백엔드 API |
| 3단계 | 서버 발송·영수증 처리·관리자 화면 실제 집계 | 백엔드 + Firebase·Apple 계정 |
| 4단계 (선택) | 웹 푸시, iOS 중요 알림 | 추가 구현·애플 승인 |
