import { Outfit_400Regular } from '@expo-google-fonts/outfit/400Regular';
import { Outfit_500Medium } from '@expo-google-fonts/outfit/500Medium';
import { Outfit_600SemiBold } from '@expo-google-fonts/outfit/600SemiBold';

import { fonts } from './index';

/** Font files for useFonts in app/_layout.tsx, keys are the family names in fonts. */
export const fontAssets = {
  [fonts.regular]: Outfit_400Regular,
  [fonts.medium]: Outfit_500Medium,
  [fonts.semiBold]: Outfit_600SemiBold,
};
