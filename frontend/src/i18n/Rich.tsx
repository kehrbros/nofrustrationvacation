import { Fragment } from 'react';

/**
 * Text of a translation with emphasis: parts in <b>…</b> are shown bold, e.g. t('install.ios.share'). Everything
 * else stays text, the translation is never set as HTML.
 */
export default function Rich({ text }: { text: string }) {
  return (
    <>
      {text.split(/(<b>.*?<\/b>)/).map((part, index) =>
        part.startsWith('<b>') && part.endsWith('</b>') ? (
          <strong key={index}>{part.slice(3, -4)}</strong>
        ) : (
          <Fragment key={index}>{part}</Fragment>
        ),
      )}
    </>
  );
}
