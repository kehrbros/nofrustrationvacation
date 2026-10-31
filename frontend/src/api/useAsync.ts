import { useCallback, useEffect, useState } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** Loads again, e.g. after a change. */
  reload: () => void;
}

/** Runs the loader on mount and whenever its dependencies change; a newer run wins over an older one. */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    loader().then(
      (result) => {
        if (active) {
          setData(result);
          setError(undefined);
          setLoading(false);
        }
      },
      (failure: unknown) => {
        if (active) {
          setError(failure);
          setLoading(false);
        }
      },
    );
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((value) => value + 1), []);
  return { data, error, loading, reload };
}
