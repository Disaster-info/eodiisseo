# 외부 API 응답 범위의 테스트 데이터

API에서 받을 수 있는 항목만 남겼다. 값은 가상이며 실호출로 수집한 재난 정보가 아니다. 테스트 표시는 `manifest.json`과 `[TEST]` 문구로 구분하고, 제공처 응답에 없는 `isTest` 같은 필드는 원본 응답 안에 추가하지 않는다.

| 파일 | 포함 정보 |
|---|---|
| [earthquake.json](scenarios/earthquake.json) | 지진 안내 재난문자 + 기상청 기온·강수형태·하늘상태 |
| [heavy-rain.json](scenarios/heavy-rain.json) | 호우 안내 재난문자 + 기상청 기온·강수형태·하늘상태 |
| [wildfire.json](scenarios/wildfire.json) | 산불 안내 재난문자 + 기상청 기온·강수형태·하늘상태 |
| [earthquake-shelters.json](scenarios/earthquake-shelters.json) | 지진옥외대피장소의 지역코드·원본 일련번호·명칭·주소·좌표·수용인원·시설면적·사용구분·전화번호 |
| [earthquake-shelters-empty.json](scenarios/earthquake-shelters-empty.json) | 대피장소 조회 결과 없음 |
| [tmap-pedestrian.json](scenarios/tmap-pedestrian.json) | 보행 경로 좌표·거리·시간·안내지점·문구 |
| [tmap-pedestrian-empty.json](scenarios/tmap-pedestrian-empty.json) | 보행 경로 없음 |
| [weather-no-data.json](scenarios/weather-no-data.json) | 기상청 자료 없음 |
| [manifest.json](scenarios/manifest.json) | 테스트 표시·파일 목록·출처·확인 상태·해석 제한 |

각 재난 파일은 외부 응답 3개를 묶은 파일이다. `disasterMessageResponse`, `weatherObservationResponse`, `weatherForecastResponse`는 파일 내부 구분 이름이며 외부 API가 이 세 응답을 한 번에 반환한다는 의미가 아니다. 날씨 격자와 시각도 재난 위치·발생시각으로 사용하지 않는다.

## 포함하지 않는 정보

- 회원·그룹·개인 GPS·체크리스트 완료·알림 발송·AI 요약·내부 수집 이력.
- 임의 재난 ID·재난 위험등급·발생/종료시각·진행 상태·피해 영향 폴리곤.
- 산불 피해면적·진화율·대응단계. 별도 API로 제공이 확인되기 전에는 만들지 않는다.
- 지진 규모·진앙. 기상청 지진정보 응답의 정확한 계약을 검증한 뒤 별도 응답 fixture로 추가한다.
- 대피소 잔여 자리·현재 운영 여부·재난 안전성이 검증된 경로.

재난문자의 `CRT_DT`는 문자 생성시각, `RCPTN_RGN_NM`은 수신지역, `EMRG_STEP_NM`은 문자 긴급단계다. 각각 재난 발생시각·피해범위·재난 심각도로 자동 치환하지 않는다. 지진옥외대피장소를 호우·산불용 시설로 임의 추천하지 않는다.

## 확인 근거

- 긴급재난문자: [공식 데이터 상세](https://www.safetydata.go.kr/disaster-data/view?dataSn=228) 및 프로젝트 외부 API 정리본의 응답 항목. 이번 파일에서는 확인된 5개 필드만 사용했다. 실제 JSON 타입·값 범위·코드는 이 프로젝트에서 실호출 검증 전이다.
- 날씨: 현재 `KmaWeatherProvider`의 `getUltraSrtNcst`/`getUltraSrtFcst` 응답 처리.
- 대피장소: 현재 `EarthquakeShelterItem` 및 `SafetydataResponseDto`의 필드와 응답 구조.
- 경로: 현재 `TmapResponseDto`의 GeoJSON 구조. 좌표·거리·시간은 응답 파싱 시험용 가상값이며 실제 도로·통행 가능 여부를 의미하지 않는다.

기존 내부 데이터용 SQL과 그 DB 적재 테스트는 제거했다. API에서 받지 않은 값을 DB에 채워 운영에서 얻을 수 있는 데이터처럼 보이게 하지 않는다.

재생성: `python mock-data/generate_scenarios.py`

검증: `backend` 폴더에서 `gradlew.bat test --tests "*ExternalApiFixtureTest"` 실행. JSON 필드 제한, 기존 대피소 DTO 역직렬화, TMAP 좌표·거리·시간 구조를 확인한다.
