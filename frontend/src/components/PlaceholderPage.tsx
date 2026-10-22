import { Empty } from 'antd';
import type { ReactNode } from 'react';

import PageHeader from './PageHeader';

interface PlaceholderPageProps {
  title: string;
  description?: ReactNode;
  extra?: ReactNode;
}

/** Page that exists in the navigation but has no content yet. */
export default function PlaceholderPage({ title, description, extra }: PlaceholderPageProps) {
  return (
    <>
      <PageHeader title={title} description={description} extra={extra} />
      <Empty style={{ marginTop: 64 }} description="Diese Seite ist noch in Arbeit." />
    </>
  );
}
