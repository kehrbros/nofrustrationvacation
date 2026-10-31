/** Design tokens of the "Noir" look: black and white with one yellow accent, font Outfit. Light mode only. */
export const colors = {
  ink: '#111111',
  muted: '#4a4a4a',
  surface: '#f4f4f2',
  border: '#111111',
  accent: '#f2b705',
  accentSoft: '#fff4cc',
  white: '#ffffff',
  danger: '#b42318',
} as const;

export const fonts = {
  regular: 'Outfit_400Regular',
  medium: 'Outfit_500Medium',
  semiBold: 'Outfit_600SemiBold',
} as const;

export const spacing = { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 } as const;

export const radius = { md: 12, lg: 16, pill: 999 } as const;

export const borderWidth = 2;

export const typography = {
  title: { fontFamily: fonts.semiBold, fontSize: 28, lineHeight: 34, color: colors.ink },
  heading: { fontFamily: fonts.semiBold, fontSize: 20, lineHeight: 26, color: colors.ink },
  body: { fontFamily: fonts.regular, fontSize: 16, lineHeight: 22, color: colors.ink },
  muted: { fontFamily: fonts.regular, fontSize: 14, lineHeight: 20, color: colors.muted },
  button: { fontFamily: fonts.medium, fontSize: 16, lineHeight: 20 },
} as const;
