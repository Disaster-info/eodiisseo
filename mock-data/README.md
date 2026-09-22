# 시연용 테스트 재난 데이터

요구사항 FR-TEST-001~006, 9/18 교수님 피드백 4번에 대응합니다.

- 실제 외부 API 응답과 **같은 구조**로 작성합니다 (FR-TEST-003).
- 모든 데이터에 `"isTest": true`를 넣어 운영 데이터와 구분합니다 (FR-TEST-005).
- `scenarios/`에는 시간 순서가 있는 시나리오를 둡니다.

| 파일 | 내용 |
|---|---|
| `scenarios/earthquake-pohang.json` | 지진 발생 → 여진 → 종료 |
| `scenarios/heavy-rain-escalation.json` | 호우주의보 → 호우경보 격상 → 해제 |
| `scenarios/wildfire-stage.json` | 산불 발생 → 대응 2단계 → 진화 완료 |

필수 항목: 재난 유형, 발생지역, 위험수준, 발생시각, 재난 원문, 좌표 또는 행정지역, 재난 상태
