# 지진 독립 Probe — 실행 전 검수 절차

이 도구는 `EarthquakeProbeCli`이며 Spring Boot 애플리케이션이 아니다. 기본은 CHECK이고, 원문 저장은 지원하지 않는다. 실제 API 호출은 별도 사용자 승인 전 금지한다. 기존 Scheduler, Coordinator, Worker, Projection, JDBC를 실행하지 않는다.

## 범위
- PORTAL: 고정 HTTPS Portal getEqkMsg + serviceKey.
- HUB: 고정 HTTPS Hub getEqkMsg + authKey.
- 다른 operation, endpoint override, 자동 fallback/retry/날짜 순회 없음.
- 초기 CLI는 page1/rows1, 명시한 단일 날짜(실행일 KST 기준 전날), route당 승인 파일1개·최대1GET만 허용한다. 두 route를 검증하려면 각각 별도 파일과 명시적 실행 승인이 필요하며 최초 계획 전체 상한은2GET이다.
- Probe 인스턴스는 route별 시도1회, 전체2회까지이다. CLI는 매번 한 route만 선택한다. 실패/키 공급 실패도 시도 예산을 환급하지 않는다.
- CLI 실행 시 `<approval-file>.consumed`를 CREATE_NEW로 기록한다. startup/timeout/불명 요청 이후에도 지우거나 재사용하지 않는다. 이 marker는 로컬 재시작 방어이며 파일 복사/삭제를 수행하는 운영자를 통제하거나 외부 API exactly-once를 보장하지 않는다.

## 승인 체크리스트 — 현재 미확인
Portal과 Hub 각각 현재 세부 API 개발키 승인, 키 만료, 실제 출발IP 허용, quota scope·잔여량, 정확한 날짜/route/최대1회, 메타데이터 출력 파일의 ACL·보관기간을 확인한다. `.env`나 인증키를 보고서/명령줄/전체 URL에 넣지 않는다. 공개 자동승인 안내나 이전 승인은 현재 계정 상태를 대신하지 않는다.
승인 파일의 true 값은 사용자의 확인 진술이며 제공기관 승인을 자동 검증하는 기능이 아니다. 운영 Collection 승인 artifact로 사용할 수 없다.

## 승인 파일 형식 (미승인 템플릿)
승인된 비공개 임시 디렉터리 안에 UTF-8 plain key=value로 작성한다. 아래 PLACEHOLDER는 그대로 실행할 수 없다. 중복/추가 필드와 원문 저장 옵션은 거부한다. 파일에 키·토큰·비밀번호를 넣지 않는다.

```properties
route=PORTAL
from=YYYY-MM-DD
to=YYYY-MM-DD
page=1
rows=1
format=JSON
expires-at=REVIEWED_ISO_INSTANT
key-valid-until=CONFIRMED_ISO_INSTANT
max-requests=1
development-api-approved=false
allowed-ip-approved=false
remaining-quota-approved=false
metadata-access-approved=false
approval-reference=REQUIRED:EXPLICIT_REVIEW
body-storage=NONE
```
HUB는 route=HUB의 별도 파일을 사용한다. from/to는 모두 승인된 같은 날짜이며 실행일 전날이어야 한다. 현재 retention 경계가 불명확하므로 초기 도구는 최근 과거 하루로만 더 좁게 제한한다. 시간대의 공식 의미를 입증한 것은 아니다.

## Gradle 설정을 변경하지 않는 실행 준비
다음 init script를 승인된 임시 경로 `probe.init.gradle`에 저장한다. 이 문서 작성 단계에서는 실제 승인 파일을 만들거나 실행하지 않는다.

```groovy
allprojects {
    plugins.withId('java') {
        ['earthquakeProbeCheck': '--check', 'earthquakeProbeApproved': '--execute-approved'].each { taskName, mode ->
            tasks.register(taskName, JavaExec) {
                dependsOn classes
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'com.disasterinfo.eodiisseo.disaster.collection.EarthquakeProbeCli'
                jvmArgs '-Djdk.httpclient.redirects.retrylimit=1',
                    '-Djdk.httpclient.disableRetryConnect=true',
                    '-Djdk.httpclient.enableAllMethodRetry=false'
                doFirst {
                    args mode,
                        "--route=${System.getenv('EODIISSEO_PROBE_ROUTE')}",
                        "--from=${System.getenv('EODIISSEO_PROBE_FROM')}",
                        "--to=${System.getenv('EODIISSEO_PROBE_TO')}",
                        '--page=1', '--rows=1', '--format=JSON',
                        "--approval-file=${System.getenv('EODIISSEO_PROBE_APPROVAL_FILE')}"
                }
            }
        }
    }
}
```

PowerShell에서는 다음 비밀정보가 아닌 파라미터만 설정한다. 실제 날짜·파일은 승인 후 지정한다.

```powershell
Set-Location 'C:\Users\dkemf\source\eodiisseo-collection-unification'
$probeInit = Join-Path $env:TEMP 'probe.init.gradle'
$env:EODIISSEO_PROBE_ROUTE = 'PORTAL' # HUB는 별도 명시적 승인 후 실행
$env:EODIISSEO_PROBE_FROM = '<approved YYYY-MM-DD>'
$env:EODIISSEO_PROBE_TO = '<same approved YYYY-MM-DD>'
$env:EODIISSEO_PROBE_APPROVAL_FILE = '<approved private temp approval file>'
.\backend\gradlew.bat -p backend -I $probeInit earthquakeProbeCheck `
  "-PbuildOutputDir=$env:TEMP\eodiisseo-probe-run-build" `
  --project-cache-dir "$env:TEMP\eodiisseo-probe-run-cache" --no-daemon --console=plain --max-workers=1
```
CHECK는 키 Supplier나 HTTP client를 만들지 않고 marker도 쓰지 않는다. 승인이 불완전하면 실패한다.

별도 실제 실행 승인 후에만 선택 route의 키를 승인된 secret 공급 수단으로 개발 프로세스 환경에 주입한다. 키 값을 명령에 직접 쓰거나 표시하지 않는다.
- PORTAL: EODIISSEO_PROBE_PORTAL_KEY
- HUB: EODIISSEO_PROBE_HUB_KEY

그 후 동일한 비밀정보 없는 인자와 init script로 task만 `earthquakeProbeApproved`로 바꾼다. 이 작업이 실제 GET을 수행하는 단계이며 이번에는 실행하지 않는다.
HTTP/TLS debug logging, proxy/redirect, 미검토 JDK는 허용하지 않는다. 실패 시 marker를 지우고 재실행하지 말고 결과 검토와 별도 승인을 받는다.

## 관측 결과와 한계
stdout은 메타데이터 JSON만 출력한다. 알 수 없는 resultMsg와 Content-Type은 그대로 출력하지 않고 고정 분류로 바꾼다. 원문은 hash·byte 수만 남기며 주요 필드는 존재 여부·자료형·시각 형식만 기록한다. 예외 cause·URL·키·원문 값은 출력하지 않는다.

`httpNormal`, `providerNormal`, `earthquakeRowsReceived`를 구분한다. 마지막 값은 행을 수신했다는 의미이지 좌표·사건·페이지 완전성의 승인 결과가 아니다. conditions에 모순·누락·미검증이 남으면 수집 SUCCESS나 Coverage로 취급하지 않는다. 종료코드0도 운영 승인이 아니다.

Probe의 parse 결과는 기존 공식 decoder나 VerifiedIncidentLinkPort의 대체물이 아니다. 시각 형식만 관측하며 시간대·사건 관계를 추정하지 않는다.
Portal의 serviceKey/ServiceKey 표기, 필수/선택 차이, Hub 보존기간·403 원인, cnt 참조, 월별 tmSeq의 범위, 정정·취소 순서, snapshot·증분 계약은 미해결이다.