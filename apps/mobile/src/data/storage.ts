// 기기 저장소 (iOS·Android): 보안 저장소(Keychain·Keystore). 로그인 토큰·위치 설정처럼 작은 값만. 웹은 storage.web.ts
import * as SecureStore from 'expo-secure-store';

export const getItem = (key: string) => SecureStore.getItemAsync(key);
export const setItem = (key: string, value: string) => SecureStore.setItemAsync(key, value);
export const removeItem = (key: string) => SecureStore.deleteItemAsync(key);
