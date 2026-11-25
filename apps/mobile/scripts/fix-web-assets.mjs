// 웹 배포본 자산 경로 보정 (npm run build:web 이 expo export 다음에 실행)
// Cloudflare Pages(wrangler)는 이름이 node_modules 인 폴더를 업로드하지 않음 (고정 규칙, 설정으로 못 바꿈 — cloudflare/workers-sdk#3615)
// expo export 는 아이콘 폰트(Ionicons.ttf)·expo-router 이미지를 dist/assets/node_modules/ 아래에 둠
// → Cloudflare 배포본에서 404 → 아이콘이 모두 빈칸 (Expo Go 는 폰트를 앱 안에서 읽어 정상이라 개발 중엔 안 보임)
// 그래서 폴더 이름을 assets/vendor 로 바꾸고, 빌드 결과 안의 경로 문자열도 같이 바꿈
// 사용: node scripts/fix-web-assets.mjs [dist 폴더, 기본 dist]
import { existsSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dist = process.argv[2] ?? 'dist';
const from = join(dist, 'assets', 'node_modules');
const to = join(dist, 'assets', 'vendor');
const TEXT = /\.(js|mjs|html|css|json|map)$/;

const walk = (dir, visit) => {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) { visit(path, true); walk(path, visit); } else visit(path, false);
  }
};

if (!existsSync(from)) {
  console.log('fix-web-assets: assets/node_modules 없음 — 바꿀 것 없음');
} else {
  if (existsSync(to)) throw new Error(`fix-web-assets: ${to} 가 이미 있어 이름을 바꿀 수 없음`);
  renameSync(from, to);
  let changed = 0;
  walk(dist, (path, isDir) => {
    if (isDir || !TEXT.test(path)) return;
    const text = readFileSync(path, 'utf8');
    if (!text.includes('assets/node_modules/')) return;
    writeFileSync(path, text.replaceAll('assets/node_modules/', 'assets/vendor/'));
    changed++;
  });
  // 경로를 하나도 못 바꿨으면 Expo 출력 형식이 바뀐 것 → 배포 전에 알 수 있게 빌드 실패
  if (changed === 0) throw new Error('fix-web-assets: assets/node_modules 참조를 찾지 못함 (Expo 출력 형식 확인 필요)');
  console.log(`fix-web-assets: assets/node_modules → assets/vendor (참조 파일 ${changed}개 수정)`);
}
// 바꾼 경로가 전부 실제 파일을 가리키는지 확인 (하나라도 없으면 배포본에서 404 → 빌드 실패로 알림)
// 참고: 소스맵(--source-maps)을 켜면 이 치환으로 열 위치가 어긋남. 지금 배포 명령은 소스맵을 만들지 않음
const missing = new Set();
walk(dist, (path, isDir) => {
  if (isDir || !TEXT.test(path)) return;
  for (const [, ref] of readFileSync(path, 'utf8').matchAll(/["'(]\/?(assets\/vendor\/[^"')?#]+)/g)) {
    if (!existsSync(join(dist, ref))) missing.add(ref);
  }
});
if (missing.size) throw new Error(`fix-web-assets: 없는 자산을 가리킴: ${[...missing].join(', ')}`);
// 다른 곳에 node_modules 폴더가 남아 있으면 Cloudflare 에서 또 빠짐 → 빌드 실패로 알림
const left = [];
walk(dist, (path, isDir) => { if (isDir && path.endsWith('node_modules')) left.push(path); });
if (left.length) throw new Error(`fix-web-assets: 업로드되지 않는 폴더가 남음: ${left.join(', ')}`);
