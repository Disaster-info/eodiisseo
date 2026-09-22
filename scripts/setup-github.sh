#!/usr/bin/env bash
# 어디있어 GitHub 저장소 초기 설정
# 필요: GitHub CLI(gh) 설치 후 `gh auth login` 완료
# 사용법: ./scripts/setup-github.sh <조직명 또는 내 아이디> [저장소명]
set -euo pipefail

OWNER="${1:?사용법: $0 <조직명|아이디> [저장소명]}"
REPO="${2:-eodiisseo}"
FULL="$OWNER/$REPO"

echo "▶ 1. 저장소 생성 및 main 푸시"
git init -b main 2>/dev/null || true
git add .
git commit -m "chore: 프로젝트 초기 설정" 2>/dev/null || echo "  (커밋할 변경 없음)"
gh repo create "$FULL" --public --source=. --remote=origin --push \
  --description "지진·호우·산불 재난정보 통합 대응 앱 | 캡스톤 팀 살려주세요"

echo "▶ 2. develop 브랜치 생성 및 기본 브랜치 지정"
git checkout -b develop
git push -u origin develop
gh repo edit "$FULL" --default-branch develop \
  --enable-squash-merge --enable-merge-commit=false --enable-rebase-merge=false \
  --delete-branch-on-merge

echo "▶ 3. 라벨 생성"
# 기본 라벨 정리
for l in "enhancement" "good first issue" "help wanted" "invalid" "question" "wontfix" "duplicate"; do
  gh label delete "$l" --repo "$FULL" --yes 2>/dev/null || true
done
while IFS='|' read -r name color desc; do
  gh label create "$name" --repo "$FULL" --color "$color" --description "$desc" --force
done <<'LABELS'
feature|1D76DB|새 기능
bug|D73A4A|버그
docs|0075CA|문서
refactor|A2EEEF|리팩터링
test|BFD4F2|테스트
chore|EDEDED|빌드·설정
mobile|7057FF|React Native 앱
backend|0E8A16|Spring Boot 서버
data|FBCA04|재난정보 수집·정규화
gis|C5DEF5|지도·대피소·경로·영향지역
ai|F9D0C4|AI 요약·대응 안내
infra|5319E7|배포·CI·DB
필수|B60205|요구사항 구분: 필수
고도화|FF9F1C|요구사항 구분: 고도화
조건부|C2E0C6|요구사항 구분: 조건부
LABELS

echo "▶ 4. 브랜치 보호 (main, develop: PR + 승인 1명)"
PROTECT='{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 1, "dismiss_stale_reviews": true },
  "restrictions": null
}'
for b in main develop; do
  if echo "$PROTECT" | gh api -X PUT "repos/$FULL/branches/$b/protection" --input - >/dev/null 2>&1; then
    echo "  ✓ $b 보호 설정 완료"
  else
    echo "  ✗ $b 보호 설정 실패 — 비공개 저장소는 GitHub Pro/Team(학생은 GitHub Education)이 필요합니다."
  fi
done

echo
echo "✅ 완료: https://github.com/$FULL"
echo "남은 작업: 팀원 초대(Settings > Collaborators), CODEOWNERS의 @TODO 수정, Projects 보드 생성"
