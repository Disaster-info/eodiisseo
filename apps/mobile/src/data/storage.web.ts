// 기기 저장소 (웹): 브라우저 localStorage — 새로고침·탭을 닫아도 유지
// ponytail: 페이지 스크립트(XSS)가 읽을 수 있음. 백엔드가 refresh 토큰을 httpOnly 쿠키로 주면 토큰은 그쪽으로 옮김
export const getItem = async (key: string) => localStorage.getItem(key);
export const setItem = async (key: string, value: string) => localStorage.setItem(key, value);
export const removeItem = async (key: string) => localStorage.removeItem(key);
