import { renderToString } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import OnboardingChecklist, { checklistItems } from './OnboardingChecklist';

describe('OnboardingChecklist', () => {
  it('links open steps to their config pages', () => {
    const items = checklistItems({ registered: true, payoutAccountLinked: false, agentConfigured: true, complete: false });

    expect(items.map((item) => [item.title, item.done])).toEqual([
      ['Registrieren', true],
      ['PayPal verknüpfen', false],
      ['Agent hinterlegen', true],
    ]);
    expect(items[1].path).toBe('/provider/auszahlungskonto');
    expect(items[2].path).toBe('/provider/agents');
  });

  it('shows progress and marks open steps', () => {
    const html = renderToString(
      <MemoryRouter>
        <OnboardingChecklist
          status={{ registered: true, payoutAccountLinked: false, agentConfigured: false, complete: false }}
        />
      </MemoryRouter>,
    );

    expect(html).toContain('Onboarding-Checkliste');
    expect(html).toContain('1/3');
    expect(html).toContain('PayPal verknüpfen: offen');
    expect(html).toContain('Agent hinterlegen: offen');
    expect(html).toContain('Registrieren: erledigt');
  });
});
