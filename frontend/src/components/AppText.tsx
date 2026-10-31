import { Text, type TextProps } from 'react-native';

import { typography } from '../theme';

export type TextVariant = 'title' | 'heading' | 'body' | 'muted';

/** Text in one of the typography variants of the theme. */
export function AppText({ variant = 'body', style, ...props }: TextProps & { variant?: TextVariant }) {
  return <Text style={[typography[variant], style]} {...props} />;
}
