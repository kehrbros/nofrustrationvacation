import { ConfigProvider, Spin } from 'antd';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import KiwiLoader from './KiwiLoader';

describe('KiwiLoader', () => {
  it('names itself for screen readers', () => {
    const html = renderToString(<KiwiLoader />);
    expect(html).toContain('role="img"');
    expect(html).toContain('aria-label="Wird geladen …"');
    expect(html).toContain('va-kiwi-medium');
  });

  it('stays silent next to a text', () => {
    const html = renderToString(<KiwiLoader size="small" decorative />);
    expect(html).toContain('aria-hidden="true"');
    expect(html).not.toContain('role="img"');
  });

  it('replaces the dots of every antd Spin', () => {
    const html = renderToString(
      <ConfigProvider spin={{ indicator: <KiwiLoader size="fill" /> }}>
        <Spin />
      </ConfigProvider>,
    );
    expect(html).toContain('va-kiwi');
    expect(html).toContain('ant-spin-dot');
  });
});
