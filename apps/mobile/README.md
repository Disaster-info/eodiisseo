# 어디있어 (프론트 UI)

백엔드(`develop` 브랜치 `backend/`) API 에 연결되는 React Native(Expo SDK 58) 앱입니다. 목업 데이터는 없으며, 서버 주소는 `.env.local` 의 `EXPO_PUBLIC_API_URL` 로 정합니다.

## 실행

```bash
npm install
npx expo run:ios       # iOS 개발 빌드 설치·실행 (Xcode 필요)
npx expo run:android   # 안드로이드 개발 빌드 설치·실행
npx expo start --web   # 웹
```

## 서버 배포 (OpenStack)

웹 버전과 백엔드를 OpenStack VM 에서 Docker 로 운영합니다. 순서와 설명은 [deploy/README.md](deploy/README.md) 참고.

```bash
cd deploy && cp .env.example .env && docker compose up -d --build   # 서버에서 실행 → http://<서버IP>
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
├─ data/types.ts           공통 타입·표시용 상수
├─ data/server.ts          백엔드 API 호출 + 화면 타입으로 변환, useLoad 훅
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

## 참고

- 앱은 소셜 로그인 네이티브 모듈을 포함한 개발 빌드(`npx expo run:ios|android` 또는 EAS 빌드)로 실행합니다. Expo Go 는 지원하지 않습니다.
- 운영자 화면은 백엔드 회원 역할에 `OPS` 가 있는 계정으로 로그인하면 열립니다.
