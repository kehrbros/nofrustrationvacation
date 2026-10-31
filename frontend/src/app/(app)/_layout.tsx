import { Stack } from 'expo-router';

import { colors, fonts } from '../../theme';

export default function AppLayout() {
  return (
    <Stack
      screenOptions={{
        headerShadowVisible: false,
        headerStyle: { backgroundColor: colors.white },
        headerTintColor: colors.ink,
        headerTitleStyle: { fontFamily: fonts.semiBold },
        contentStyle: { backgroundColor: colors.white },
      }}
    >
      <Stack.Screen name="index" options={{ title: 'Meine Reisen' }} />
    </Stack>
  );
}
