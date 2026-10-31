import { TokenResponse } from 'expo-auth-session';

import type { TokenStorage } from './tokenStorage';

/** An access token is refreshed this many seconds before it expires. */
const EXPIRY_MARGIN_SECONDS = 30;

type Listener = (tokens: TokenResponse | null) => void;

/**
 * Tokens of the logged in user, shared by the UI (AuthProvider) and the API client. Hands out a valid access token and
 * refreshes it with the refresh token when it is about to expire. A failed refresh ends the session.
 */
export class Session {
  private tokens: TokenResponse | null = null;
  private refreshing: Promise<TokenResponse | null> | null = null;
  private readonly listeners = new Set<Listener>();

  constructor(
    private readonly storage: TokenStorage,
    private readonly refreshTokens: (refreshToken: string) => Promise<TokenResponse>,
  ) {}

  get current(): TokenResponse | null {
    return this.tokens;
  }

  /** Loads the tokens of the last app start. */
  async restore(): Promise<TokenResponse | null> {
    this.set(await this.storage.load());
    return this.tokens;
  }

  async start(tokens: TokenResponse): Promise<void> {
    await this.storage.save(tokens);
    this.set(tokens);
  }

  async end(): Promise<void> {
    await this.storage.clear();
    this.set(null);
  }

  /** A valid access token, refreshed if necessary, or null without a session. */
  async accessToken(): Promise<string | null> {
    const tokens = this.tokens;
    if (!tokens) {
      return null;
    }
    if (TokenResponse.isTokenFresh(tokens, -EXPIRY_MARGIN_SECONDS)) {
      return tokens.accessToken;
    }
    // concurrent requests share one refresh, the refresh token is rotated and only valid once
    this.refreshing ??= this.refresh(tokens).finally(() => {
      this.refreshing = null;
    });
    return (await this.refreshing)?.accessToken ?? null;
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private async refresh(tokens: TokenResponse): Promise<TokenResponse | null> {
    if (!tokens.refreshToken) {
      await this.end();
      return null;
    }
    try {
      const refreshed = await this.refreshTokens(tokens.refreshToken);
      // keep the ID token for the logout, the refresh response may not contain one
      const merged = new TokenResponse({
        ...refreshed.getRequestConfig(),
        refreshToken: refreshed.refreshToken ?? tokens.refreshToken,
        idToken: refreshed.idToken ?? tokens.idToken,
      });
      await this.start(merged);
      return merged;
    } catch {
      await this.end();
      return null;
    }
  }

  private set(tokens: TokenResponse | null) {
    this.tokens = tokens;
    this.listeners.forEach((listener) => listener(tokens));
  }
}
