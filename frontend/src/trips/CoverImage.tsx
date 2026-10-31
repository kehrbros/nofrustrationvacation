import { useEffect, useState } from 'react';

import { fetchCoverImage } from './tripsApi';

interface CoverImageProps {
  tripId: number;
  hasCoverImage: boolean;
  className?: string;
  /** Hint in the corner while there is no photo, e.g. on the current trip. */
  showHint?: boolean;
}

/** Title photo of a trip, or a drawn placeholder while there is none (generation is still open). */
export default function CoverImage({ tripId, hasCoverImage, className, showHint }: CoverImageProps) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!hasCoverImage) {
      return;
    }
    let objectUrl: string | null = null;
    let active = true;
    void fetchCoverImage(tripId).then((blob) => {
      if (active && blob) {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      }
    });
    return () => {
      active = false;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [tripId, hasCoverImage]);

  if (hasCoverImage && url) {
    return <img className={`va-cover ${className ?? ''}`} src={url} alt="" />;
  }
  // a few landscapes, chosen by the trip id so a trip keeps its picture
  const variant = tripId % 4;
  return (
    <div className={`va-cover va-cover-placeholder va-cover-${variant} ${className ?? ''}`} aria-hidden="true">
      <svg width="56" height="40" viewBox="0 0 56 40" fill="none" stroke="#4a4a4a" strokeWidth="2.2">
        {variant === 0 && (
          <>
            <path d="M2 38 L18 14 L28 28 L36 18 L54 38 Z" />
            <circle cx="44" cy="8" r="5" />
          </>
        )}
        {variant === 1 && (
          <>
            <path d="M2 30 Q14 22 28 30 T54 30" />
            <path d="M10 30 V12 L22 6 V30" />
            <path d="M34 30 V16 H46 V30" />
          </>
        )}
        {variant === 2 && (
          <>
            <path d="M2 26 Q10 20 18 26 T34 26 T54 26" />
            <path d="M2 34 Q10 28 18 34 T34 34 T54 34" />
          </>
        )}
        {variant === 3 && (
          <>
            <path d="M6 36 H50" />
            <path d="M14 36 V18 A14 14 0 0 1 42 18 V36" />
          </>
        )}
      </svg>
      {showHint && <span className="va-cover-hint">Noch kein Titelfoto</span>}
    </div>
  );
}
