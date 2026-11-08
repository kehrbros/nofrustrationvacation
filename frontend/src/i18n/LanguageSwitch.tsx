import { currentLanguage, LANGUAGES, setLanguage, t } from '.';

/** Choice of the UI language in the profile menu, see Docs/i18n.md. */
export default function LanguageSwitch() {
  const active = currentLanguage();
  return (
    <div className="va-menu-item">
      <span id="language-label">{t('language.label')}</span>
      <div role="group" aria-labelledby="language-label" className="va-tabs va-language-switch">
        {LANGUAGES.map((language) => (
          <button
            key={language}
            type="button"
            lang={language}
            aria-pressed={language === active}
            onClick={() => setLanguage(language)}
          >
            {t(`language.${language}`)}
          </button>
        ))}
      </div>
    </div>
  );
}
