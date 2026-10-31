import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '../../auth';
import { AppText } from '../../components/AppText';
import { Button } from '../../components/Button';
import { Screen } from '../../components/Screen';
import { getProfile, type AppUserDto } from '../../generated/api';
import { colors, radius, spacing } from '../../theme';

/** Start page after the login. Placeholder until the trips API exists, shows the profile as API check. */
export default function HomeScreen() {
  const { signOut } = useAuth();
  const [profile, setProfile] = useState<AppUserDto | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    getProfile()
      .then(({ data }) => (data ? setProfile(data) : setFailed(true)))
      .catch(() => setFailed(true));
  }, []);

  return (
    <Screen>
      <View style={styles.card}>
        <AppText variant="heading">Noch keine Reisen</AppText>
        <AppText variant="muted">Hier erscheinen bald deine Routen und Ereignisse, die sie betreffen.</AppText>
      </View>
      <View style={styles.spacer} />
      <AppText variant="muted">
        {profile ? `Angemeldet als ${profile.username}` : failed ? 'Profil konnte nicht geladen werden' : ' '}
      </AppText>
      <Button label="Abmelden" variant="secondary" onPress={() => void signOut()} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: spacing.lg,
    gap: spacing.sm,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
  },
  spacer: { flex: 1 },
});
