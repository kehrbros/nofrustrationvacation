import { Flex, Typography } from 'antd';
import type { ReactNode } from 'react';

import { brand } from '../theme';

interface PageHeaderProps {
  title: ReactNode;
  /** Small line above the title. */
  eyebrow?: ReactNode;
  description?: ReactNode;
  /** Buttons on the right. */
  extra?: ReactNode;
}

/** Title block at the top of a page. */
export default function PageHeader({ title, eyebrow, description, extra }: PageHeaderProps) {
  return (
    <Flex justify="space-between" align="flex-end" wrap gap={16}>
      <div>
        {eyebrow && <Typography.Text type="secondary">{eyebrow}</Typography.Text>}
        <Typography.Title level={1} style={{ margin: 0, fontSize: 40, fontWeight: 800, letterSpacing: '-0.02em' }}>
          {title}
        </Typography.Title>
        {description && <div style={{ color: brand.muted, fontSize: 16, marginTop: 4 }}>{description}</div>}
      </div>
      {extra && (
        <Flex gap={10} wrap>
          {extra}
        </Flex>
      )}
    </Flex>
  );
}
