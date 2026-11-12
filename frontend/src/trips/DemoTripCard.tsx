import { CompassOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';

import KiwiLoader from '../components/KiwiLoader';
import { currentLanguage, t } from '../i18n';
import { createDemoTrip, demoTripsAvailable } from './demoTripApi';

/**
 * "Load demo trip" on My trips, only where the server offers demo trips: creates a ready-made trip over the South
 * Island with bookings and incidents and opens it, see Docs/demo-trip.md.
 */
export default function DemoTripCard() {
  const navigate = useNavigate();
  const [available, setAvailable] = useState(false);
  const [creating, setCreating] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    void demoTripsAvailable().then((value) => {
      if (active) {
        setAvailable(value);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  if (!available) {
    return null;
  }

  const create = async () => {
    setCreating(true);
    setFailed(false);
    try {
      const result = await createDemoTrip(currentLanguage());
      navigate(`/reisen/${result.tripId}`);
    } catch {
      setFailed(true);
      setCreating(false);
    }
  };

  return (
    <section className="va-card va-demo-trip" aria-labelledby="demo-trip-title">
      <div className="va-card-body">
        <h2 id="demo-trip-title" className="va-card-title">
          <CompassOutlined aria-hidden="true" /> {t('demoTrip.title')}
        </h2>
        <p className="va-muted">{t('demoTrip.text')}</p>
        {creating ? (
          <div className="va-row va-gap" role="status">
            <KiwiLoader size="small" decorative />
            <span className="va-small va-muted">{t('demoTrip.creating')}</span>
          </div>
        ) : (
          <button type="button" className="va-button va-button-outline va-block" onClick={() => void create()}>
            {t('demoTrip.create')}
          </button>
        )}
        {failed && <Alert type="error" showIcon message={t('demoTrip.failed')} />}
      </div>
    </section>
  );
}
