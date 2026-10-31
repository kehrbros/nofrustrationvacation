import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';

import { configureApiClient } from '../api/apiClient';
import { AuthProvider, session, useAuth } from '../auth';
import { colors } from '../theme';
import { fontAssets } from '../theme/fonts';

configureApiClient(session);
void SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  return (
    <AuthProvider session={session}>
      <StatusBar style="dark" />
      <RootStack />
    </AuthProvider>
  );
}

/** The screens in (app) need a login, login is only shown without one. */
function RootStack() {
  const { status } = useAuth();
  const [fontsLoaded, fontError] = useFonts(fontAssets);
  const ready = status !== 'loading' && (fontsLoaded || fontError !== null);

  useEffect(() => {
    if (ready) {
      void SplashScreen.hideAsync();
    }
  }, [ready]);

  if (!ready) {
    return null;
  }
  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.white } }}>
      <Stack.Protected guard={status === 'signedIn'}>
        <Stack.Screen name="(app)" />
      </Stack.Protected>
      <Stack.Protected guard={status === 'signedOut'}>
        <Stack.Screen name="login" />
      </Stack.Protected>
      <Stack.Screen name="auth/[step]" />
    </Stack>
  );
}
