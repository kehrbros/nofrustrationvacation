import { CheckOutlined, CloudServerOutlined, ShoppingCartOutlined } from '@ant-design/icons';
import { Button, Flex, Layout, Typography } from 'antd';
import { useState, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { useNavigate } from 'react-router';

import { roleHome } from '../role/navigation';
import { useRole, type Role } from '../role/RoleContext';
import { brand } from '../theme';

interface RoleOption {
  role: Role;
  icon: ReactNode;
  title: string;
  description: string;
}

const options: RoleOption[] = [
  {
    role: 'consumer',
    icon: <ShoppingCartOutlined />,
    title: 'Ich kaufe Tokens',
    description:
      'Consumer: Du nennst deinen Höchstpreis und bekommst automatisch das günstigste passende Angebot.',
  },
  {
    role: 'provider',
    icon: <CloudServerOutlined />,
    title: 'Ich biete Kapazität an',
    description:
      'Provider: Du verkaufst ungenutzte Tokens deines LLM-Zugangs zu deinem Preis und siehst, was sie einbringen.',
  },
];

/**
 * First page after the first login: the user picks the primary role. It can be switched later in the header.
 */
export default function OnboardingPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const { role, setRole } = useRole();
  const [choice, setChoice] = useState<Role>(role === 'provider' ? 'provider' : 'consumer');
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.sub;

  const proceed = () => {
    setRole(choice);
    navigate(roleHome[choice], { replace: true });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ borderBottom: `2px solid ${brand.ink}`, display: 'flex', alignItems: 'center' }}>
        <span style={{ fontWeight: 800, fontSize: 24, letterSpacing: '-0.01em' }}>VacantAgent</span>
      </Layout.Header>
      <Layout.Content style={{ width: '100%', maxWidth: 1000, margin: '0 auto', padding: '56px 32px', boxSizing: 'border-box' }}>
        <Flex vertical gap={36}>
          <div style={{ textAlign: 'center' }}>
            <Typography.Title level={1} style={{ margin: 0, fontSize: 48, fontWeight: 800, letterSpacing: '-0.02em' }}>
              {`Willkommen${username ? `, ${username}` : ''}.`}
            </Typography.Title>
            <div style={{ fontSize: 18, color: brand.muted, marginTop: 8 }}>
              Wie möchtest du starten? Du kannst die Rolle später jederzeit oben rechts wechseln.
            </div>
          </div>
          <div
            role="radiogroup"
            aria-label="Primäre Rolle"
            style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 24 }}
          >
            {options.map((option) => {
              const checked = option.role === choice;
              return (
                <button
                  key={option.role}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  onClick={() => setChoice(option.role)}
                  style={{
                    textAlign: 'left',
                    border: `3px solid ${brand.ink}`,
                    background: checked ? brand.ink : brand.white,
                    color: checked ? brand.white : brand.ink,
                    borderRadius: 20,
                    padding: 32,
                    font: 'inherit',
                    cursor: 'pointer',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 14,
                  }}
                >
                  <Flex justify="space-between" align="center" style={{ width: '100%' }}>
                    <span style={{ fontSize: 36, color: checked ? brand.accent : brand.ink }}>{option.icon}</span>
                    <span
                      style={{
                        width: 28,
                        height: 28,
                        borderRadius: '50%',
                        boxSizing: 'border-box',
                        border: checked ? 'none' : `3px solid ${brand.ink}`,
                        background: checked ? brand.accent : 'transparent',
                        color: brand.ink,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      {checked && <CheckOutlined />}
                    </span>
                  </Flex>
                  <span style={{ fontSize: 28, fontWeight: 800 }}>{option.title}</span>
                  <span style={{ fontSize: 16, color: checked ? '#d8d8d8' : brand.muted }}>{option.description}</span>
                </button>
              );
            })}
          </div>
          <Flex justify="center">
            <Button className="va-accent" size="large" style={{ height: 56, padding: '0 36px', fontSize: 18 }} onClick={proceed}>
              Weiter
            </Button>
          </Flex>
        </Flex>
      </Layout.Content>
    </Layout>
  );
}
