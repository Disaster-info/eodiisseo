// 기기 저장소 (웹): 브라우저 localStorage — 새로고침·탭을 닫아도 유지
// 페이지 스크립트(XSS)가 읽을 수 있음 → refresh 토큰은 여기 두지 않고 서버의 HttpOnly 쿠키로만 (api.ts, M5). 짧게 쓰는 access 토큰만 저장
export const getItem = async (key: string) => localStorage.getItem(key);
export const setItem = async (key: string, value: string) => localStorage.setItem(key, value);
export const removeItem = async (key: string) => localStorage.removeItem(key);
