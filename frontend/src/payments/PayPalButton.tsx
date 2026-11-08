import { Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';

import { t } from '../i18n';
import { loadPayPal } from './paypalSdk';

/**
 * PayPal button for an order created by the backend. Approving opens PayPal in a popup and comes back to the same
 * page; only if the SDK cannot be loaded, a link to PayPal (redirect, back via the return URL) is shown instead.
 */
export default function PayPalButton({ clientId, currency, orderId, approveUrl, disabled, onApprove, onCancel, onError }: {
  clientId: string | null;
  currency: string;
  orderId: string | null;
  /** Redirect fallback. */
  approveUrl: string | null;
  disabled?: boolean;
  onApprove: () => Promise<void> | void;
  onCancel?: () => void;
  onError?: () => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'fallback'>(clientId && orderId ? 'loading' : 'fallback');
  // the callbacks change on every render, the button must not be rendered again for that
  const callbacks = useRef({ onApprove, onCancel, onError });
  callbacks.current = { onApprove, onCancel, onError };

  useEffect(() => {
    if (!clientId || !orderId || !container.current) {
      setState('fallback');
      return;
    }
    let closed = false;
    let buttons: { close(): Promise<void> } | null = null;
    const target = container.current;
    loadPayPal({ clientId, currency, intent: 'authorize' }).then(
      (paypal) => {
        if (closed) return;
        const instance = paypal.Buttons({
          fundingSource: paypal.FUNDING.PAYPAL,
          style: { layout: 'vertical', color: 'gold', shape: 'pill', label: 'pay', height: 48 },
          createOrder: () => orderId,
          onApprove: () => callbacks.current.onApprove(),
          onCancel: () => callbacks.current.onCancel?.(),
          onError: () => callbacks.current.onError?.(),
        });
        if (!instance.isEligible()) {
          setState('fallback');
          return;
        }
        buttons = instance;
        target.replaceChildren();
        instance.render(target).then(() => !closed && setState('ready'), () => !closed && setState('fallback'));
      },
      () => !closed && setState('fallback'),
    );
    return () => {
      closed = true;
      void buttons?.close().catch(() => undefined);
    };
  }, [clientId, currency, orderId]);

  return (
    <div className={`va-paypal ${disabled ? 'va-paypal-disabled' : ''}`} aria-busy={state === 'loading'}>
      <div ref={container} hidden={state === 'fallback'} />
      {state === 'loading' && <div className="va-empty"><Spin size="small" /></div>}
      {state === 'fallback' && approveUrl && (
        <a className="va-button va-button-accent va-block" href={approveUrl}>{t('payment.toPayPal')}</a>
      )}
    </div>
  );
}
