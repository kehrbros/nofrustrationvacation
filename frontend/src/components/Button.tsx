import { ActivityIndicator, Pressable, StyleSheet, Text, type PressableProps } from 'react-native';

import { borderWidth, colors, radius, spacing, typography } from '../theme';

export type ButtonVariant = 'primary' | 'accent' | 'secondary';

type ButtonProps = Omit<PressableProps, 'children'> & {
  label: string;
  variant?: ButtonVariant;
  loading?: boolean;
};

/** Pill button: primary black, accent yellow for the main action of a screen, secondary outlined. */
export function Button({ label, variant = 'primary', loading = false, disabled, style, ...props }: ButtonProps) {
  const inactive = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy: loading }}
      disabled={inactive}
      style={(state) => [
        styles.base,
        styles[variant],
        state.pressed && styles.pressed,
        inactive && styles.disabled,
        typeof style === 'function' ? style(state) : style,
      ]}
      {...props}
    >
      {loading ? (
        <ActivityIndicator color={labelColors[variant]} />
      ) : (
        <Text style={[typography.button, { color: labelColors[variant] }]}>{label}</Text>
      )}
    </Pressable>
  );
}

const labelColors: Record<ButtonVariant, string> = {
  primary: colors.white,
  accent: colors.ink,
  secondary: colors.ink,
};

const styles = StyleSheet.create({
  base: {
    minHeight: 48,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    borderWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primary: { backgroundColor: colors.ink, borderColor: colors.ink },
  accent: { backgroundColor: colors.accent, borderColor: colors.ink },
  secondary: { backgroundColor: colors.white, borderColor: colors.ink },
  pressed: { opacity: 0.8 },
  disabled: { opacity: 0.5 },
});
