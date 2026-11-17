# 웹 버전 배포 이미지 (OpenStack VM 에서 docker compose 로 실행 — deploy/README.md)
# 1단계(build): Node 로 웹 빌드 → dist/   2단계: nginx 가 dist/ 를 서비스하고 /api/ 는 백엔드로 전달
# 두 단계로 나눠 최종 이미지에는 node_modules 없이 빌드 결과(수 MB)와 nginx 만 남음

FROM node:22-slim AS build
WORKDIR /app
# 패키지 목록만 먼저 복사 → 소스만 바뀌면 npm ci 단계는 캐시를 다시 씀 (빌드가 빨라짐)
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# EXPO_PUBLIC_* 는 빌드할 때 JS 코드에 그대로 박힘 → 값을 바꾸면 이미지를 다시 빌드해야 함 (비밀값 넣지 않기)
# API_URL 을 비워 두면 웹은 같은 서버의 /api/... 로 요청 → nginx 가 백엔드로 전달 (CORS 설정 필요 없음)
ARG EXPO_PUBLIC_API_URL=""
ARG EXPO_PUBLIC_TMAP_APP_KEY=""
ARG EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID=""
ARG EXPO_PUBLIC_KAKAO_REST_API_KEY=""
RUN npx expo export -p web

FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
