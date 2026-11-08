import { useEffect, useState, type ReactNode } from 'react';

import { currentLanguage, i18n } from '.';

/**
 * Renders the app again from scratch when the language changes, so texts computed outside of components (t() in
 * helpers) and the antd locale switch as well. Open dialogs stay open, they are part of the URL.
 */
export default function LanguageRoot({ children }: { children: (language: string) => ReactNode }) {
  const [language, setLanguage] = useState(currentLanguage);

  useEffect(() => {
    const changed = () => setLanguage(currentLanguage());
    i18n.on('languageChanged', changed);
    return () => i18n.off('languageChanged', changed);
  }, []);

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  return <div key={language} style={{ display: 'contents' }}>{children(language)}</div>;
}
