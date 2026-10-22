import { LogoutOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Flex, Layout, Menu, Segmented } from 'antd';
import { useAuth } from 'react-oidc-context';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';

import OnboardingChecklist from '../onboarding/OnboardingChecklist';
import { ProviderOnboardingProvider, useProviderOnboarding } from '../onboarding/ProviderOnboardingContext';
import { navigation, ONBOARDING_PATH, roleHome, roleLabel, selectedNavigationPath } from '../role/navigation';
import { isOperator } from '../role/operator';
import { useRole, type Role } from '../role/RoleContext';
import { brand } from '../theme';

const { Header, Content } = Layout;

/** Pages, with the onboarding checklist at the side while the provider onboarding is incomplete. */
function ContentWithChecklist() {
  const { status } = useProviderOnboarding();
  const page = (
    <Flex vertical gap={28} style={{ flex: '1 1 600px', minWidth: 0 }}>
      <Outlet />
    </Flex>
  );
  if (!status || status.complete) {
    return page;
  }
  return (
    <Flex gap={28} wrap align="flex-start">
      {page}
      <div style={{ flex: '0 1 300px', minWidth: 260, position: 'sticky', top: 24 }}>
        <OnboardingChecklist status={status} />
      </div>
    </Flex>
  );
}

/**
 * Base layout of the dashboard: header with the logo, the navigation of the current role, the role switcher and
 * the user menu; pages in the content area.
 */
export default function DashboardLayout() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { role, setRole } = useRole();
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.sub;
  const operator = isOperator(auth.user);

  if (!role || (role === 'operator' && !operator)) {
    return <Navigate to={ONBOARDING_PATH} replace />;
  }

  const roles: Role[] = operator ? ['consumer', 'provider', 'operator'] : ['consumer', 'provider'];
  const selected = selectedNavigationPath(role, location.pathname);

  const switchRole = (next: Role) => {
    setRole(next);
    navigate(roleHome[next]);
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          borderBottom: `2px solid ${brand.ink}`,
          display: 'flex',
          alignItems: 'center',
          gap: 28,
          height: 'auto',
          minHeight: 72,
          flexWrap: 'wrap',
        }}
      >
        <a
          href={roleHome[role]}
          onClick={(event) => {
            event.preventDefault();
            navigate(roleHome[role]);
          }}
          style={{ fontWeight: 800, fontSize: 24, letterSpacing: '-0.01em', color: brand.ink, whiteSpace: 'nowrap' }}
        >
          VacantAgent
        </a>
        <Menu
          className="va-nav"
          mode="horizontal"
          selectedKeys={selected ? [selected] : []}
          items={navigation[role].map((item) => ({ key: item.path, label: item.label }))}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none', background: 'transparent' }}
        />
        <Flex align="center" gap={12}>
          <Segmented<Role>
            className="va-role-switch"
            aria-label="Rolle wechseln"
            value={role}
            options={roles.map((value) => ({ value, label: roleLabel[value] }))}
            onChange={switchRole}
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
              size={44}
              style={{
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
        </Flex>
      </Header>
      <ProviderOnboardingProvider enabled={role === 'provider'}>
        <Content style={{ width: '100%', maxWidth: 1200, margin: '0 auto', padding: 32, boxSizing: 'border-box' }}>
          <ContentWithChecklist />
        </Content>
      </ProviderOnboardingProvider>
    </Layout>
  );
}
