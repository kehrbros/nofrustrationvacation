import type { ThemeConfig } from 'antd';

/** Brand colors of the "Noir" look derived from the logo: black and white with one yellow accent. */
export const brand = {
  ink: '#111111',
  muted: '#4a4a4a',
  surface: '#f4f4f2',
  accent: '#f2b705',
  accentSoft: '#fff4cc',
  white: '#ffffff',
} as const;

export const theme: ThemeConfig = {
  token: {
    colorPrimary: brand.ink,
    colorInfo: brand.ink,
    colorLink: brand.ink,
    colorLinkHover: brand.muted,
    colorLinkActive: brand.ink,
    colorText: brand.ink,
    colorTextSecondary: brand.muted,
    colorBorder: brand.ink,
    colorBgLayout: brand.white,
    fontFamily: "Outfit, system-ui, -apple-system, 'Segoe UI', sans-serif",
    fontSize: 15,
    borderRadius: 12,
    borderRadiusLG: 16,
    controlHeight: 44,
    lineWidth: 2,
  },
  components: {
    // header height and padding per screen width in index.css
    Layout: { headerBg: brand.white, bodyBg: brand.white },
    Button: { borderRadius: 999, borderRadiusLG: 999, borderRadiusSM: 999, primaryShadow: 'none', fontWeight: 500 },
    Menu: {
      horizontalItemSelectedColor: brand.ink,
      horizontalItemHoverColor: brand.ink,
      itemColor: brand.muted,
      activeBarHeight: 3,
      horizontalLineHeight: '68px',
    },
    Segmented: {
      itemSelectedBg: brand.ink,
      itemSelectedColor: brand.white,
      itemColor: brand.ink,
      trackBg: brand.white,
      trackPadding: 3,
      borderRadius: 999,
      borderRadiusSM: 999,
    },
    Card: { headerFontSize: 18, colorBorderSecondary: brand.ink },
    Table: { headerBg: brand.ink, headerColor: brand.white, borderColor: '#eeeeee' },
  },
};
