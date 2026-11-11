import kiwiUrl from '../assets/kiwi.svg';
import { t } from '../i18n';

interface KiwiLoaderProps {
  /**
   * small: inline next to a text, medium: in a box or list (32 px), large: whole page (64 px), fill: size given by the
   * surrounding element (antd Spin).
   */
  size?: 'small' | 'medium' | 'large' | 'fill';
  /** Read out by screen readers, default "Loading …"; shown text next to it stays with the caller. */
  label?: string;
  /** true: a text next to it already says what is loading, screen readers skip the icon. */
  decorative?: boolean;
  className?: string;
}

/**
 * Loading indicator for anything that takes longer: the waiting kiwi (kiwi-waiting.svg in the repository root, animated
 * in the SVG itself, at rest with prefers-reduced-motion). Also the default
 * indicator of every antd Spin (ConfigProvider in main.tsx). See Docs/architecture.md (Frontend, Ladeanzeige).
 */
export default function KiwiLoader({ size = 'medium', label, decorative = false, className }: KiwiLoaderProps) {
  return (
    <span className={`va-kiwi va-kiwi-${size}${className ? ` ${className}` : ''}`}
      {...(decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': label ?? t('common.loading') })}>
      <img src={kiwiUrl} alt="" draggable={false} />
    </span>
  );
}
