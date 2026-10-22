import { CheckOutlined, RightOutlined } from '@ant-design/icons';
import { Progress } from 'antd';
import { useNavigate } from 'react-router';

import type { ProviderOnboardingDto } from '../generated/api';
import { AGENTS_PATH, PAYOUT_ACCOUNT_PATH } from '../role/navigation';
import { brand } from '../theme';

interface ChecklistItem {
  done: boolean;
  title: string;
  description: string;
  /** Config page of the step, undefined if it cannot be changed in the app. */
  path?: string;
}

export function checklistItems(status: ProviderOnboardingDto): ChecklistItem[] {
  return [
    { done: status.registered, title: 'Registrieren', description: 'Konto bei VacantAgent angelegt' },
    {
      done: status.payoutAccountLinked,
      title: 'PayPal verknüpfen',
      description: 'Dorthin zahlen wir deine Einnahmen aus',
      path: PAYOUT_ACCOUNT_PATH,
    },
    {
      done: status.agentConfigured,
      title: 'Agent hinterlegen',
      description: 'Mindestens ein LLM-Zugang mit API-Key',
      path: AGENTS_PATH,
    },
  ];
}

/**
 * Provider onboarding as a checklist, shown next to the pages until every step is done. The order of the steps does
 * not matter; an open step leads directly to its config page.
 */
export default function OnboardingChecklist({ status }: { status: ProviderOnboardingDto }) {
  const navigate = useNavigate();
  const items = checklistItems(status);
  const done = items.filter((item) => item.done).length;

  return (
    <aside
      aria-label="Onboarding-Checkliste"
      style={{ border: `2px solid ${brand.ink}`, borderRadius: 16, padding: 20, background: brand.white }}
    >
      <div style={{ fontSize: 13, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }}>
        Onboarding
      </div>
      <div style={{ fontSize: 22, fontWeight: 800, margin: '4px 0 2px' }}>Fast startklar</div>
      <div style={{ color: brand.muted, fontSize: 14 }}>Erledige die Schritte in beliebiger Reihenfolge.</div>
      <Progress
        percent={Math.round((done / items.length) * 100)}
        format={() => `${done}/${items.length}`}
        strokeColor={brand.accent}
        trailColor="#eeeeee"
        style={{ margin: '12px 0 8px' }}
      />
      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {items.map((item) => {
          const open = !item.done && item.path;
          const content = (
            <>
              <span
                aria-hidden
                style={{
                  flex: 'none',
                  width: 26,
                  height: 26,
                  borderRadius: '50%',
                  boxSizing: 'border-box',
                  border: item.done ? 'none' : `2px solid ${brand.ink}`,
                  background: item.done ? brand.accent : 'transparent',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 13,
                }}
              >
                {item.done && <CheckOutlined />}
              </span>
              <span style={{ flex: 1, minWidth: 0 }}>
                <span
                  style={{
                    display: 'block',
                    fontWeight: 700,
                    textDecoration: item.done ? 'line-through' : 'none',
                    color: item.done ? brand.muted : brand.ink,
                  }}
                >
                  {item.title}
                </span>
                <span style={{ display: 'block', fontSize: 13, color: brand.muted }}>{item.description}</span>
              </span>
              {open && <RightOutlined style={{ fontSize: 12 }} />}
            </>
          );
          const style = {
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            width: '100%',
            padding: '10px 12px',
            borderRadius: 12,
            textAlign: 'left' as const,
            font: 'inherit',
            color: brand.ink,
          };
          return (
            <li key={item.title}>
              {open ? (
                <button
                  type="button"
                  onClick={() => navigate(item.path!)}
                  aria-label={`${item.title}: offen`}
                  style={{ ...style, border: 'none', background: brand.accentSoft, cursor: 'pointer' }}
                >
                  {content}
                </button>
              ) : (
                <div style={style} aria-label={`${item.title}: ${item.done ? 'erledigt' : 'offen'}`}>
                  {content}
                </div>
              )}
            </li>
          );
        })}
      </ol>
    </aside>
  );
}
