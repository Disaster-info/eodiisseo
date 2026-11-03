# 어디있어 (프론트 UI)

백엔드 없이 목업 데이터로 동작하는 React Native(Expo SDK 58) 화면입니다.

## 실행

```bash
npm install
npx expo start      # 휴대폰 Expo Go 로 QR 스캔 / i: iOS 시뮬레이터 / a: 안드로이드 / w: 웹
```

## 앱 식별자 (Android·iOS)

`app.json` 에 설정된 앱 고유 ID입니다. Firebase(FCM 푸시 알림) 앱 등록과 스토어 등록에 같은 값을 씁니다.

| 플랫폼 | 항목 (`app.json`) | 값 |
|---|---|---|
| Android | `expo.android.package` | `com.disasterinfo.eodiisseo` |
| iOS | `expo.ios.bundleIdentifier` | `com.disasterinfo.eodiisseo` |
| 딥링크 | `expo.scheme` | `eodiisseo` |

- 스토어에 출시한 뒤에는 바꿀 수 없으므로 임의로 변경하지 않습니다.
- `ios/`·`android/` 폴더는 빌드할 때 `app.json` 으로 자동 생성되므로(CNG) 직접 만들거나 고치지 않습니다.
- Firebase 앱 등록 후 받은 `google-services.json`(Android)·`GoogleService-Info.plist`(iOS)는 `app.json` 의 `android.googleServicesFile`·`ios.googleServicesFile` 로 연결합니다.

## 폴더 구조

```
src/
├─ app/                    Expo Router 라우트 (파일 = 화면 주소). 화면 코드는 screens/ 를 연결만 함
│  ├─ _layout.tsx          앱 시작점: 전역 상태 + 테마 + 스택. 로그인 여부로 화면 접근 제한
│  ├─ (tabs)/              하단 탭 5개 (index=홈, shelter, group, checklist, my)
│  ├─ disasters/           /disasters 재난 목록, /disasters/[id] 상세
│  ├─ shelters/[id].tsx    대피소 상세        route/[id].tsx  대피 경로
│  ├─ groups/[id].tsx      안심그룹 상세
│  └─ login, consent, location, areas, noti, chat, ops
├─ data/mock.ts            목업 데이터·타입 (API 연동 시 여기를 교체)
├─ store/AppContext.tsx    전역 상태 (로그인·설정·그룹 등)
├─ components/ui.tsx       공통 UI(Card, Btn, Chip…), 색상·스타일, confirm/notify
├─ components/disaster.tsx 재난 카드·등급 배지
└─ screens/                화면 구현 (auth, location, home, shelter, group, checklist, my, chat, ops)
```

## 새 화면 추가 방법

1. `src/screens/<기능>/XxxScreen.tsx` 작성 (테마 반영을 위해 `useApp()` 을 한 번은 호출)
2. `src/app/xxx.tsx` 에 `export { default } from '../screens/<기능>/XxxScreen';` 한 줄 작성
3. 로그인 후에만 보여야 하면 `src/app/_layout.tsx` 의 `Stack.Protected guard={!!member}` 안에 `<Stack.Screen name="xxx" />` 추가
4. 이동할 곳에서 `router.push('/xxx')` 호출 (`expo-router` 의 `router`)

## 테스트용 초대 코드

- `FRD-1234`: 참여 성공
- `FULL-0000`: 인원 초과(10명)로 참여 실패
