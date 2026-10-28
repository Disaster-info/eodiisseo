---
name: 어디있어
description: 공식 재난 정보를 내 위치와 내 사람들 기준으로, 도로표지 한 장처럼 보여 주는 앱
colors:
  sign-blue: "#0B5CAD"
  sign-green: "#0E7A4F"
  sign-red: "#D7261E"
  sign-yellow: "#F5C400"
  on-sign: "#FFFFFF"
  on-yellow: "#16181B"
  primary: "#0B5CAD"
  fill: "#0B5CAD"
  primary-soft: "#E3EDF8"
  notice-text: "#0A4C8F"
  danger: "#B3201A"
  danger-soft: "#FBE6E4"
  danger-text: "#B3201A"
  warning: "#8A5A00"
  success: "#0E7A4F"
  text: "#16181B"
  sub: "#5C6168"
  border: "#D9DCD8"
  concrete-ground: "#F2F3F1"
  card: "#FFFFFF"
  primary-dark: "#7DB2EC"
  fill-dark: "#1F67B5"
  primary-soft-dark: "#16263A"
  notice-text-dark: "#A9CBF2"
  danger-dark: "#FF8F85"
  danger-soft-dark: "#3A1A18"
  danger-text-dark: "#FFB0A8"
  warning-dark: "#F2C230"
  success-dark: "#5FC89A"
  text-dark: "#EDEEEC"
  sub-dark: "#A3A8AE"
  border-dark: "#30353A"
  concrete-ground-dark: "#121416"
  card-dark: "#1C1F22"
typography:
  display:
    fontFamily: "system-ui"
    fontSize: "34px"
    fontWeight: 900
    lineHeight: "40px"
    fontFeature: "tnum"
  headline:
    fontFamily: "system-ui"
    fontSize: "30px"
    fontWeight: 800
    lineHeight: "36px"
    letterSpacing: "-0.5px"
  nav-title:
    fontFamily: "system-ui"
    fontSize: "22px"
    fontWeight: 800
  title:
    fontFamily: "system-ui"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: "26px"
  body:
    fontFamily: "system-ui"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: "24px"
  body-bold:
    fontFamily: "system-ui"
    fontSize: "17px"
    fontWeight: 700
    lineHeight: "24px"
  secondary:
    fontFamily: "system-ui"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: "22px"
  label:
    fontFamily: "system-ui"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18px"
  badge:
    fontFamily: "system-ui"
    fontSize: "13px"
    fontWeight: 700
rounded:
  badge: "6px"
  sign-inner: "8px"
  control: "10px"
  panel: "12px"
  sheet: "16px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
components:
  button-primary:
    backgroundColor: "{colors.fill}"
    textColor: "{colors.on-sign}"
    typography: "{typography.body-bold}"
    rounded: "{rounded.control}"
    padding: "0 12px"
    height: "52px"
  button-outline:
    backgroundColor: "{colors.card}"
    textColor: "{colors.primary}"
    typography: "{typography.body-bold}"
    rounded: "{rounded.control}"
    padding: "0 12px"
    height: "52px"
  sign-red:
    backgroundColor: "{colors.sign-red}"
    textColor: "{colors.on-sign}"
    rounded: "{rounded.panel}"
    padding: "4px"
  sign-yellow:
    backgroundColor: "{colors.sign-yellow}"
    textColor: "{colors.on-yellow}"
    rounded: "{rounded.panel}"
    padding: "12px"
  sign-blue:
    backgroundColor: "{colors.sign-blue}"
    textColor: "{colors.on-sign}"
    rounded: "{rounded.panel}"
    padding: "4px"
  sign-green:
    backgroundColor: "{colors.sign-green}"
    textColor: "{colors.on-sign}"
    rounded: "{rounded.panel}"
    padding: "4px"
  sign-button-solid:
    backgroundColor: "{colors.on-sign}"
    textColor: "{colors.sign-green}"
    rounded: "{rounded.control}"
    height: "48px"
  sign-button-outline:
    textColor: "{colors.on-sign}"
    rounded: "{rounded.control}"
    height: "48px"
  card:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.panel}"
    padding: "16px"
  chip:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    typography: "{typography.secondary}"
    rounded: "{rounded.pill}"
    padding: "9px 14px"
    height: "44px"
  chip-selected:
    backgroundColor: "{colors.fill}"
    textColor: "{colors.on-sign}"
  badge:
    typography: "{typography.badge}"
    rounded: "{rounded.badge}"
    padding: "3px 8px"
  input:
    backgroundColor: "{colors.concrete-ground}"
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "12px 14px"
  segmented:
    backgroundColor: "{colors.border}"
    rounded: "{rounded.control}"
    padding: "3px"
    height: "44px"
  segmented-selected:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
  notice:
    backgroundColor: "{colors.primary-soft}"
    textColor: "{colors.notice-text}"
    rounded: "{rounded.control}"
    padding: "12px"
  notice-error:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger-text}"
---

# Design System: 어디있어

## Overview

**Creative North Star: "도로표지 (The Road Sign)"**

앱은 길을 묻기 전에 답한다. 화면은 중립 콘크리트 회색 바탕 위에 놓인 한국 도로표지판이다. 색은 판면 안에만 산다: 청색 안내판은 평시 상태, 노란 주의판(바깥 빨간 테두리)은 주의보, 빨간 규제판(안쪽 흰 테두리)은 경보, 녹색 안내판은 대피 안내. 판 하나에 결정 하나. 바탕·카드·목록은 색을 쓰지 않고, 위험도와 행동은 표지판의 종류(모양 + 색 + 글자)로 읽힌다.

밀도는 낮고 글자는 크다. 시스템 글꼴의 굵은 고딕 한 줄 안내, 자릿수 폭이 고정된 큰 거리 숫자(km), 의미 단위 타입 스케일(본문 17, 하한 13)이 고령자와 한 손 사용을 기준으로 맞춰져 있다. 표지판 색은 실제 도로표지처럼 낮·밤 모두 같은 값이고, 바탕·글자 색만 라이트/다크 테마로 바뀐다.

거부하는 기본값: 흰 카드 리스트 + 파란 바로가기 그리드 + 날씨가 주인공인 홈.

**Key Characteristics:**
- 중립 회색 바탕, 색은 표지판 판면에만
- 위험수준 = 표지판 종류: 경보 > 주의보 > 중립 카드 순의 크기 램프
- 판면 위 글자는 한 가지 색, 위계는 크기·굵기로
- 시스템 글꼴, 굵은 고딕, tabular 숫자
- 모든 글자/바탕 쌍 4.5:1 이상 (라이트·다크 모두)
- 터치 높이 44 이상, 목록 행 52 이상

## Colors

중립 콘크리트 회색 바탕에 도로표지 네 가지 색만 판면으로 올라오는, 색이 드문 팔레트.

### Primary
- **안내판 청색 (Guide Blue)** (`sign-blue`): 평시 상태 판('발령 중인 경보 없음'), 정보 등급 배지 바탕. 흰 글자 6.67:1.
- **글자용 청색 (Ink Blue)** (`primary` / 다크 `primary-dark`): 링크, 아이콘, 탭 활성색, 강조 글자. 글자·아이콘 전용.
- **채움 청색 (Fill Blue)** (`fill` / 다크 `fill-dark`): 흰 글자를 얹는 버튼 바탕, 선택된 칩, 켜진 스위치 트랙. 다크에서 흰 글자 5.73:1.

### Secondary
- **대피 녹색 (Evacuation Green)** (`sign-green`): 대피 안내판 전용. 흰 글자 5.36:1. 판 안의 주 버튼은 흰 바탕 + 이 녹색 글자.
- **성공 녹색 (Outside Green)** (`success` / `success-dark`): '영향지역 밖', 정상 상태 글자, 진행 막대.

### Tertiary
- **규제 적색 (Regulatory Red)** (`sign-red`): 경보 판, 경보 배지, 119 전화 버튼. 흰 글자 5.02:1.
- **주의 황색 (Caution Yellow)** (`sign-yellow`): 주의보 판과 배지. 글자는 `on-yellow`(10.82:1). 회색 바탕과의 경계가 묻히므로(1.48:1) 판에는 반드시 바깥 빨간 테두리를 두른다.
- **위험 글자 적색 (Danger Ink)** (`danger` / `danger-dark`): 화면 위 글자·아이콘으로 쓰는 위험(영향지역 포함 인원, 파괴적 동작). `danger-soft` + `danger-text`는 오류 안내 띠.
- **주의 글자 황토 (Warning Ink)** (`warning` / `warning-dark`): 수집 실패(stale) 안내 글자.

### Neutral
- **콘크리트 바탕 (Concrete Ground)** (`concrete-ground` / `concrete-ground-dark`): 화면 바탕, 입력칸 바탕.
- **카드 (Card)** (`card` / `card-dark`): 중립 카드, 탭 바·헤더 바, 시트.
- **본문 글자 (Text)** (`text` / `text-dark`): 본문·제목. 라이트 바탕 대비 15.98:1.
- **보조 글자 (Sub)** (`sub` / `sub-dark`): 메타 정보, 꺼진 아이콘, '판단 불가'. 라이트 바탕 5.61:1, 다크 7.71:1.
- **경계선 (Border)** (`border` / `border-dark`): 구분선(hairline), 칩 테두리, 세그먼트 바탕, 꺼진 스위치.
- **안내 띠 (Notice)** (`primary-soft` + `notice-text`): 일반 안내 띠와 지도 자리.

### Named Rules
**The Color-In-The-Panel Rule.** 표지판 네 가지 색(`sign-*`)은 판면·등급 배지·응급 버튼 안에만 칠한다. 바탕, 카드, 목록 행은 중립 색만 쓴다.

**The Primary-Is-Ink Rule.** `primary`는 글자와 아이콘 전용, `fill`은 흰 글자를 얹는 꽉 찬 바탕 전용이다. 다크 `primary-dark`는 절대 버튼 바탕이 되지 않는다(흰 글자가 묻힌다).

**The Red-Means-Danger Rule.** 빨강은 위험에만 쓴다: 경보, 파괴적 동작(삭제·탈퇴·제외), 영향지역 포함. 선택 상태는 `fill`/`primary`, 진행 상태(진행중·해제)는 색이 아니라 글자로 표시한다.

**The One-Ink Rule.** 판면 위 글자는 한 가지 색(흰색, 노란 판은 `on-yellow`)만 쓴다. 빨간 판 위 옅은 보조색은 4.14:1로 미달이었으므로, 위계는 크기와 굵기로만 만든다.

**The Contrast Floor Rule.** 모든 글자/바탕 쌍은 라이트·다크 모두 4.5:1 이상. 브랜드 버튼도 예외가 아니다(카카오 `#191919` on `#FEE500` 13.75:1).

## Typography

**Display Font:** 시스템 글꼴 (iOS SF Pro / Apple SD Gothic Neo, Android Roboto / Noto Sans CJK KR)
**Body Font:** 시스템 글꼴 (동일)

**Character:** 도로표지처럼 굵은 고딕 한 가지. 웹폰트 없이 시스템 글꼴이 시스템 글자 크기 설정을 그대로 따라 커진다.

### Hierarchy
- **Display** (900, 34/40, tabular-nums): 대피 안내판의 큰 거리 숫자. 단위 'km'는 body-bold로 붙인다.
- **Headline** (800, 30/36, -0.5): 경보 판의 등급명. 판 위에서는 900으로 올린다.
- **Nav Title** (800, 22): 탭 헤더 제목과 홈 헤더의 기준 위치. 헤더 높이가 고정이라 글자 확대는 1.3배까지만.
- **Title** (700, 20/26): 주의보 판 등급명(판 위에서는 900), 평시 판 제목. 대피소 이름은 24/30 800으로 한 단계 키운다.
- **Body / Body Bold** (400·700, 17/24): 본문, 목록 행, 버튼 글자(700), 섹션 제목(700).
- **Secondary** (400, 15/22): 메타 정보, 칩 글자, 세그먼트 글자, 링크(600, 15/20).
- **Label** (400, 13/18): 갱신 시각·기준 같은 가장 작은 글자. 배지는 13 700. 13이 하한이다.

### Named Rules
**The Floor-Thirteen Rule.** 13보다 작은 글자는 없다. 위계가 더 필요하면 아래로 줄이지 말고 위로 키운다.

**The Tabular-Distance Rule.** 거리·시각·기온 숫자는 `tabular-nums`로 자릿수 폭을 고정한다.

## Layout

한 줄 세로 피드. 본문은 `Page` 스크롤 하나에 담기고 안쪽 여백 16, 블록 간격 12, 아래 여백 32 + 세이프 에어리어(안드로이드 edge-to-edge 대응). 넓은 화면(iPad·가로 창)에서는 최대 폭 640으로 가운데 정렬하고, 탭 헤더 제목의 시작선도 같은 칸에 맞춘다. 입력칸이 있으면 키보드를 피해 자동 스크롤한다.

홈의 읽는 순서는 위험 여부(상태 판) → 어디로 가나(대피 안내판) → 가족(안심그룹 한 줄씩) → 다른 지역 → 날씨. 비상 시 경보 판 하나가 첫 화면 맨 위를 차지하고, 평시에는 청색 안내판이 그 자리를 대신한다.

간격 단위는 4의 배수(4·8·12·16·24). 행 안 아이콘-글자 간격 8, 카드 안쪽 16, 판 안쪽 14(+ 흰 테두리 바깥 4).

### Named Rules
**The Thumb-Target Rule.** 누를 수 있는 것은 높이 44 이상(칩, 링크, 세그먼트, '…' 메뉴 버튼), 목록 행과 일반 버튼은 52 이상, 판 안 버튼은 48 이상.

## Elevation & Depth

거의 평평하다. 중립 카드와 선택된 세그먼트에만 아주 옅은 그림자 하나(네이티브 opacity 0.08, 반경 6, y 2, Android elevation 1)를 쓰고, 표지판은 그림자 없이 색과 테두리로 바탕에서 떠오른다. 누름 상태는 그림자 변화가 아니라 불투명도 0.7로 표시한다. 유일한 오버레이는 '…' 메뉴의 바닥 시트(반투명 검정 0.4 뒤판).

### Named Rules
**The Sign-Needs-No-Shadow Rule.** 표지판은 그림자를 달지 않는다. 판을 바탕에서 떼어 내는 것은 테두리(흰 안쪽 테두리, 노란 판의 빨간 바깥 테두리)다.

## Shapes

부드럽게 깎은 직사각형 한 가족. 판·카드·지도 자리 12, 버튼·입력칸·세그먼트·안내 띠 10, 판 안쪽 테두리와 세그먼트 칸 8, 배지 6, 바닥 시트 위 모서리 16, 칩만 완전한 알약형. 원형은 '…' 메뉴 버튼(44 원)뿐이다.

표지판 테두리 문법: 빨강·청색·녹색 판은 판 색 4 여백 안에 2px 흰 테두리(반경 8)를 한 번 더 두르고, 노란 판은 3px 빨간 바깥 테두리를 두른다. 위험수준 아이콘 모양은 경보 = 원(`alert-circle`), 주의보 = 삼각(`warning`), 정보 = 원 안 i(`information-circle`)로, 색에만 의존하지 않는다.

## Components

### Buttons
듬직하고 꽉 찬 한 줄 버튼.
- **Shape:** 부드럽게 깎은 모서리 (10px), 높이 52, 가로로 늘어남
- **Primary:** `fill` 바탕 + 흰 글자 17 700, 아이콘 20은 글자 앞
- **Outline:** 카드 바탕 + 1.5px 테두리와 글자 모두 같은 글자용 색(`primary`, 파괴적 동작은 `danger`)
- **Pressed:** 불투명도 0.7. 큰 글자에서는 줄바꿈된다.
- **브랜드/응급:** 카카오 로그인은 브랜드 노랑 + 어두운 글자, 119 전화는 `sign-red` 바탕 + 흰 글자.

### Chips
- **Style:** 카드 바탕, 1px `border` 테두리, 알약형, 글자 15 500, 높이 44
- **State:** 선택 시 `fill` 바탕 + 흰 글자. 빨강은 선택에 쓰지 않는다.

### Cards / Containers
- **Corner Style:** 12px
- **Background:** `card`
- **Shadow Strategy:** 옅은 그림자 하나 (Elevation 참조)
- **Border:** 없음. 카드 안 목록 행은 hairline `border` 위 구분선
- **Internal Padding:** 16 (행 묶음 카드는 위아래 4 + 행 높이 52)

### Inputs / Fields
- **Style:** `concrete-ground` 바탕, 1px `border`, 10px 모서리, 글자 17, 안쪽 12/14
- **Focus:** 시스템 기본

### Navigation
- **하단 탭 5개:** `card` 계열 바(`bar`) + 1px 위 경계선. 선택 탭은 채운 아이콘 + `primary`, 나머지는 외곽선 아이콘 + `sub`.
- **헤더:** 왼쪽 정렬 제목 22 800, 기본 높이 + 12. 홈은 앱 이름 대신 기준 위치(아이콘 + 지역명 + '현재 위치'/'직접 지정' 13).
- **상세 화면:** 네이티브 스택 헤더(뒤로가기 버튼·스와이프 유지), 제목만 넘김.

### Segmented
2~4개 중 하나 고르기. `border` 바탕 홈(10, 안쪽 3) 안에 칸마다 높이 44, 선택 칸은 카드 바탕 + 옅은 그림자 + 글자 700, 나머지는 `sub` 500.

### Badge
13 700 짧은 꼬리표. Solid는 표지판 색을 꽉 채운 등급 배지(경보·주의보·정보 + 등급 아이콘), 기본은 글자색 10% 바탕 + 같은 색 글자. 공식 등급이 없으면 '공식 등급 없음'을 `sub`로 적고 등급을 만들지 않는다. 진행 상태(진행중·해제)는 색 대신 글자 배지.

### ActionMenu
행마다 붙는 보조 동작을 44 원형 '…' 버튼 하나로 모은다. iOS는 시스템 액션 시트, Android·웹은 바닥 시트(행 높이 56, 파괴적 항목은 `danger`, '취소'는 700).

### Sign (도로표지 판) — signature
판 하나에 결정 하나. 톤 네 가지(red 경보 / yellow 주의보 / blue 평시 상태 / green 대피 안내). 진행 중인 경보·주의보만 판으로 올리고, 끝난 재난·정보·등급 없음은 중립 카드로 내린다(끝난 재난이 위험해 보이지 않게).
- **크기 램프:** 경보 판은 아이콘 30 + 등급명 30 900 + 요약 2줄, 주의보 판은 아이콘 22 + 등급명 20 900 + 요약 1줄, 그 아래는 중립 카드.
- **등장:** 홈 경보 판은 위에서 한 번 내려온다(360ms, exp ease-out). 동작 줄이기 설정이면 200ms 페이드만. 다른 경보로 바뀔 때만 다시 재생.
- **SignBtn:** 판 안 버튼, 높이 48, 글자 800. Solid = 흰 바탕 + 판 색 글자(주 행동), 기본 = 2px 판 글자색 테두리(보조 행동).

### ShelterSign (녹색 대피 안내판)
가장 가까운 대피소 하나가 주인공. 1줄: 방패 아이콘 26 + 대피소 이름 24 800. 2줄: 유형·주소(13) 왼쪽, 큰 거리 숫자(Display 34)+km와 '○○ 기준 직선'(13 700) 오른쪽. 3줄: [경로 안내] 흰 Solid + [다른 대피소 N곳] 테두리 버튼. 실제 좌표가 없으므로 방향 화살표는 그리지 않는다. 반경 안에 대피소가 없으면 그렇다고 적고 대피소 탭으로 보낸다.

## Do's and Don'ts

### Do:
- **Do** 위험수준을 표지판 종류(모양 + 색 + 글자)로 함께 표시한다: 경보 = 빨간 판 + 원형 아이콘, 주의보 = 노란 판 + 빨간 바깥 테두리 + 삼각 아이콘.
- **Do** 판 위 글자는 한 가지 색으로 쓰고 위계는 크기·굵기로 만든다.
- **Do** 흰 글자를 얹는 바탕은 `fill`, 글자·아이콘은 `primary`로 나눠 쓴다.
- **Do** 10분이 지난 구성원 위치는 영향지역 포함/밖을 확정하지 않고 모든 화면에서 '판단 불가'로 다룬다.
- **Do** 터치 높이 44 이상, 목록 행 52 이상, 판 안 버튼 48 이상을 지킨다.
- **Do** 새 글자/바탕 쌍은 라이트·다크 모두 4.5:1 이상인지 확인한다.

### Don't:
- **Don't** 바탕·카드·목록 행에 표지판 색을 칠하지 않는다.
- **Don't** 빨강을 선택 상태나 장식에 쓰지 않는다. 진행 상태도 색이 아닌 글자로 표시한다.
- **Don't** 다크 `primary-dark`를 버튼 바탕으로 쓰지 않는다.
- **Don't** 판 위에 옅은 보조 글자색을 쓰지 않는다(빨간 판에서 4.14:1로 미달).
- **Don't** 카드에 색 띠 옆 테두리(side-stripe border)를 달지 않는다.
- **Don't** 제목 위에 작은 꼬리 라벨(eyebrow)을 붙이지 않는다.
- **Don't** 텍스트 기호(+, →, ✓ 등)를 아이콘 대신 쓰지 않는다. 아이콘은 Ionicons.
- **Don't** 좌표·등급·수용인원·소요시간·방위처럼 근거 없는 값을 지어내지 않는다. 없으면 '없음'이라고 적는다.
