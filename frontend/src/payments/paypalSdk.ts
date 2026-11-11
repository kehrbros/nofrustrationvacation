/**
 * Loads the PayPal JS SDK (https://www.paypal.com/sdk/js) once per client id, currency and intent. The PayPal button
 * opens PayPal in a popup over the app instead of leaving it: the login of the app, the open sheet and an installed
 * PWA stay as they are, and no return URL is needed. See Docs/paypal.md#paypal-button-in-the-sheet.
 */

import { currentLanguage } from '../i18n';

export interface PayPalButtonsActions {
  restart?: () => void;
}

export interface PayPalButtonsOptions {
  fundingSource?: string;
  style?: Record<string, string | number | boolean>;
  createOrder: () => Promise<string> | string;
  onApprove: () => Promise<void> | void;
  onCancel?: () => void;
  onError?: (error: unknown) => void;
}

export interface PayPalButtonsInstance {
  isEligible(): boolean;
  render(container: HTMLElement): Promise<void>;
  close(): Promise<void>;
}

export interface PayPalNamespace {
  Buttons(options: PayPalButtonsOptions): PayPalButtonsInstance;
  FUNDING: { PAYPAL: string };
}

const loading = new Map<string, Promise<PayPalNamespace>>();

export interface SdkOptions {
  clientId: string;
  currency: string;
  /** Must match the intent of the order: alternatives are authorized first and captured after booking. */
  intent: 'authorize' | 'capture';
}

export function sdkUrl({ clientId, currency, intent }: SdkOptions): string {
  const params = new URLSearchParams({
    'client-id': clientId,
    currency: currency.toUpperCase(),
    intent,
    components: 'buttons',
    commit: 'true',
    // language of the PayPal button and popup follows the app (Docs/i18n.md)
    locale: currentLanguage() === 'de' ? 'de_DE' : 'en_GB',
  });
  return `https://www.paypal.com/sdk/js?${params.toString()}`;
}

/** The SDK, loaded on first use; rejects if the script cannot be loaded (offline, blocked). */
export function loadPayPal(options: SdkOptions): Promise<PayPalNamespace> {
  const url = sdkUrl(options);
  const cached = loading.get(url);
  if (cached) {
    return cached;
  }
  const promise = new Promise<PayPalNamespace>((resolve, reject) => {
    // one SDK per page: another currency needs the script again, under another namespace
    const namespace = `paypal_${loading.size}`;
    const script = document.createElement('script');
    script.src = url;
    script.async = true;
    script.dataset.namespace = namespace;
    script.onload = () => {
      const sdk = (window as unknown as Record<string, PayPalNamespace | undefined>)[namespace];
      if (sdk) {
        resolve(sdk);
      } else {
        reject(new Error('PayPal SDK did not initialize'));
      }
    };
    script.onerror = () => reject(new Error('PayPal SDK could not be loaded'));
    document.head.appendChild(script);
  });
  promise.catch(() => loading.delete(url));
  loading.set(url, promise);
  return promise;
}
