import * as SecureStore from 'expo-secure-store';
import { TokenResponse, type TokenResponseConfig } from 'expo-auth-session';

const KEY = 'vacantagent.tokens';

export interface TokenStorage {
  load(): Promise<TokenResponse | null>;
  save(tokens: TokenResponse): Promise<void>;
  clear(): Promise<void>;
}

/** Tokens in the Keychain (iOS) or the Android Keystore, so the login survives an app restart. */
export const secureTokenStorage: TokenStorage = {
  async load() {
    const stored = await SecureStore.getItemAsync(KEY);
    if (!stored) {
      return null;
    }
    try {
      return new TokenResponse(JSON.parse(stored) as TokenResponseConfig);
    } catch {
      await SecureStore.deleteItemAsync(KEY);
      return null;
    }
  },
  async save(tokens) {
    await SecureStore.setItemAsync(KEY, JSON.stringify(tokens.getRequestConfig()));
  },
  async clear() {
    await SecureStore.deleteItemAsync(KEY);
  },
};
