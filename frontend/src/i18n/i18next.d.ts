import 'i18next';

import type { de } from './de';

// typed keys: t('unknown.key') does not compile
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    resources: { translation: typeof de };
    returnNull: false;
  }
}
