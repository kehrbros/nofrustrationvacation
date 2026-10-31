import { StyleSheet, View } from 'react-native';

import { useAuth } from '../auth';
import { AppText } from '../components/AppText';
import { Button } from '../components/Button';
import { Screen } from '../components/Screen';
import { colors, spacing } from '../theme';
import { useState } from 'react';

export default function LoginScreen() {
  const { signIn, error } = useAuth();
  const [busy, setBusy] = useState(false);

  const onSignIn = async () => {
    setBusy(true);
    try {
      await signIn();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen style={styles.screen}>
      <View style={styles.intro}>
        <AppText variant="title">Willkommen</AppText>
        <AppText variant="muted">Melde dich an, um deine Reisen zu sehen.</AppText>
      </View>
      {error ? <AppText style={styles.error}>{error}</AppText> : null}
      <Button label="Anmelden" variant="accent" loading={busy} onPress={onSignIn} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: { justifyContent: 'flex-end', paddingBottom: spacing.xl },
  intro: { flex: 1, justifyContent: 'center', gap: spacing.sm },
  error: { color: colors.danger },
});
