import { Card, Descriptions, Typography } from 'antd';
import { useEffect, useState } from 'react';

import { getProfile } from '../generated/api';
import type { AppUserDto } from '../generated/api';

/**
 * Start page of the dashboard.
 */
export default function DashboardHome() {
  const [profile, setProfile] = useState<AppUserDto>();

  useEffect(() => {
    void getProfile().then(({ data }) => setProfile(data));
  }, []);

  return (
    <>
      <Typography.Title level={2}>Übersicht</Typography.Title>
      <Card title="Willkommen" loading={!profile}>
        <Descriptions column={1} items={[{ key: 'username', label: 'Benutzername', children: profile?.username }]} />
      </Card>
    </>
  );
}
