import { LogoutOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Layout, Menu } from 'antd';
import { useAuth } from 'react-oidc-context';
import { Outlet, useLocation, useNavigate } from 'react-router';

import { brand } from '../theme';
import { HOME_PATH, navigation, selectedNavigationPath } from './navigation';

const { Header, Content } = Layout;

/**
 * Base layout, mobile first: header with the app name, the navigation and the user menu; pages in the content area,
 * which gets wider padding on larger screens (index.css).
 */
export default function DashboardLayout() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.sub;
  const selected = selectedNavigationPath(location.pathname);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header className="va-header" style={{ borderBottom: `2px solid ${brand.ink}` }}>
        <a
          href={HOME_PATH}
          onClick={(event) => {
            event.preventDefault();
            navigate(HOME_PATH);
          }}
          className="va-brand"
        >
          VacantAgent
        </a>
        <Menu
          className="va-nav"
          mode="horizontal"
          selectedKeys={selected ? [selected] : []}
          items={navigation.map((item) => ({ key: item.path, label: item.label }))}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none', background: 'transparent' }}
        />
        <Dropdown
          menu={{
            items: [
              { key: 'user', label: username, disabled: true },
              { type: 'divider' },
              { key: 'logout', icon: <LogoutOutlined />, label: 'Abmelden' },
            ],
            onClick: ({ key }) => key === 'logout' && void auth.signoutRedirect(),
          }}
        >
          <Avatar
            size={40}
            aria-label="Benutzermenü"
            style={{
              flex: 'none',
              cursor: 'pointer',
              background: brand.accent,
              color: brand.ink,
              border: `2px solid ${brand.ink}`,
              fontWeight: 700,
            }}
          >
            {username?.charAt(0).toUpperCase()}
          </Avatar>
        </Dropdown>
      </Header>
      <Content className="va-content">
        <Outlet />
      </Content>
    </Layout>
  );
}
