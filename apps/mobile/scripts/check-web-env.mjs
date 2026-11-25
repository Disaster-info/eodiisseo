// 웹 빌드 전 API 주소 검사 (npm run build:web 의 첫 단계)
// EXPO_PUBLIC_* 는 빌드할 때 코드에 그대로 들어감. .env.local 은 git 에 없어서 Cloudflare·팀원 빌드에는 값이 없음
// → 값이 없으면 배포본이 모든 API 를 방문자 PC 의 http://localhost:8080 으로 보내 전부 실패 (api.ts 기본값)
// 그래서 배포에 쓸 수 없는 값이면 빌드를 멈추고 이유를 알려 줌
//   - 없음            → 실패 (Cloudflare 빌드 환경변수에 https 백엔드 주소를 넣어야 함)
//   - 빈 값("")       → 통과 (Docker: nginx 가 같은 주소의 /api 를 백엔드로 넘김)
//   - http://원격주소 → 실패 (https 페이지에서 브라우저가 차단 = mixed content)
//   - localhost       → Cloudflare(CF_PAGES=1)에서는 실패, 로컬 빌드는 경고만
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let fileEnv = {};
try {
  // expo export 와 같은 규칙으로 .env·.env.local 을 읽음 (셸 환경변수가 있으면 그쪽이 우선)
  fileEnv = require('@expo/env').parseProjectEnv(process.cwd(), { mode: 'production' }).env ?? {};
} catch { /* @expo/env 를 못 읽으면 셸 환경변수만 봄 */ }

const raw = process.env.EXPO_PUBLIC_API_URL ?? fileEnv.EXPO_PUBLIC_API_URL;
const onCloudflare = process.env.CF_PAGES === '1';
const fail = msg => { console.error(`\ncheck-web-env: ${msg}\n`); process.exit(1); };

if (raw === undefined) {
  fail('EXPO_PUBLIC_API_URL 이 없습니다. 이대로면 배포본의 모든 API 요청이 방문자 PC 의 localhost:8080 으로 가서 실패합니다.\n'
    + '  Cloudflare: Settings → Environment variables 에 EXPO_PUBLIC_API_URL=https://<백엔드 주소> 를 넣고 다시 빌드하세요.\n'
    + '  로컬: apps/mobile/.env.local 에 넣으세요 (.env.example 참고).');
}
if (raw === '') {
  console.log('check-web-env: EXPO_PUBLIC_API_URL 빈 값 → 같은 주소의 /api 로 요청 (nginx 프록시 배포용). Cloudflare 정적 배포에는 쓰지 마세요.');
} else {
  let url;
  try { url = new URL(raw); } catch { fail(`EXPO_PUBLIC_API_URL 이 올바른 주소가 아닙니다: ${raw}`); }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (local && onCloudflare) fail(`EXPO_PUBLIC_API_URL 이 ${raw} 입니다. 배포본에서는 방문자 PC 를 가리켜 모든 API 가 실패합니다. https 백엔드 주소로 바꾸세요.`);
  if (local) console.warn(`check-web-env: 경고 — API 주소가 ${raw} 입니다. 로컬 확인용으로만 쓰고 배포하지 마세요.`);
  else if (url.protocol !== 'https:') fail(`EXPO_PUBLIC_API_URL 이 https 가 아닙니다(${raw}). https 페이지에서 브라우저가 http API 요청을 차단합니다.`);
  else console.log(`check-web-env: API 주소 ${url.origin}`);
}
