import { Typography } from 'antd';
import type { ReactNode } from 'react';

import { brand } from '../theme';

interface BalanceHeroProps {
  greeting: ReactNode;
  amount: string | undefined;
  caption: ReactNode;
  actions?: ReactNode;
}

/** Black hero block with the large balance of a start page. */
export default function BalanceHero({ greeting, amount, caption, actions }: BalanceHeroProps) {
  return (
    <section
      style={{
        background: brand.ink,
        color: brand.white,
        borderRadius: 16,
        padding: 32,
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'space-between',
        alignItems: 'flex-end',
        gap: 24,
      }}
    >
      <div>
        <div style={{ fontSize: 15, color: '#c8c8c8' }}>{greeting}</div>
        <Typography.Title
          level={1}
          style={{ color: brand.white, margin: '8px 0 0', fontSize: 52, fontWeight: 800, letterSpacing: '-0.02em' }}
        >
          {amount ?? '–'}
        </Typography.Title>
        <div style={{ fontSize: 14, color: '#c8c8c8' }}>{caption}</div>
      </div>
      {actions && <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>{actions}</div>}
    </section>
  );
}
