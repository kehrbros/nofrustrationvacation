import { AimOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Alert } from 'antd';
import { useEffect, useState } from 'react';

import Sheet from '../components/Sheet';
import { formatNumber, t } from '../i18n';
import type { LinePoint } from '../trips/model';
import { useTripContext } from '../trips/tripContext';
import { formatDateTime, localDateTimeIso, toDateTimeInput } from '../trips/time';
import { snapToLines, SNAP_PIXELS } from './snap';
import {
  DIRECTIONS,
  INCIDENT_TYPES,
  newTestIncidentId,
  placeOnRoute,
  reportTestIncident,
  TestIncidentError,
  type IncidentType,
  type RoutePlacement,
  type TestIncident,
  type TestIncidentResult,
  type TravelDirection,
} from './testIncidentApi';
import { forgetTestIncident, storedTestIncidents, storeTestIncident, type StoredTestIncident } from './testIncidentStore';

type Step = 'type' | 'place' | 'details' | 'result';

/**
 * Test tool "Störung melden" (role tester): kind, a point tapped on the map (near a route of the trip: placed onto
 * that route by the backend, so the incident hits it), times, delay and text; posted to the manual test source, which runs it through the normal flow (match,
 * incident, assessment), see Docs/traffic-feed.md#testquelle-manual. Reports from this device are listed in the
 * first step and can be changed (same id) or ended. Shows what became of it; the trip reloads when closing.
 */
export default function TestIncidentSheet() {
  const { trip, routeLines, setPointPicker, setPickedPoint, reload, close } = useTripContext();
  const [step, setStep] = useState<Step>('type');
  const [entries, setEntries] = useState<StoredTestIncident[]>(storedTestIncidents);
  // id of the report being changed, null: a new one
  const [editingId, setEditingId] = useState<string | null>(null);
  const [type, setType] = useState<IncidentType | null>(null);
  const [point, setPoint] = useState<LinePoint | null>(null);
  // route the spot was tapped next to, the backend places it onto that route; null: as tapped
  const [onRoute, setOnRoute] = useState<number | null>(null);
  const [placement, setPlacement] = useState<RoutePlacement | null>(null);
  const [picking, setPicking] = useState(false);
  const [roadRef, setRoadRef] = useState('');
  const [direction, setDirection] = useState<TravelDirection | ''>('');
  const [validFrom, setValidFrom] = useState(() => toDateTimeInput(new Date().toISOString()));
  const [validTo, setValidTo] = useState('');
  const [delay, setDelay] = useState('');
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<TestIncidentResult | null>(null);
  const [ended, setEnded] = useState(false);

  // the map picks no more points and drops the pin once the wizard is gone
  useEffect(() => () => {
    setPointPicker(null);
    setPickedPoint(null);
  }, [setPointPicker, setPickedPoint]);

  const choosePoint = (chosen: LinePoint, routeId: number | null) => {
    setPoint(chosen);
    setOnRoute(routeId);
    setPlacement(null);
    setPickedPoint(chosen);
    if (routeId == null) {
      return;
    }
    // the backend places it exactly onto the segment the route drives and names the road there
    placeOnRoute(trip.id, routeId, chosen).then((placed) => {
      const exact = { latitude: placed.lat, longitude: placed.lon };
      setPoint(exact);
      setPickedPoint(exact);
      setPlacement(placed);
      setRoadRef((current) => current.trim() ? current : placed.roadRef ?? '');
    }, () => {
      // the frontend snap stays, reporting places it again
    });
  };
  const startPicking = () => {
    setPicking(true);
    setPointPicker((tapped, metersPerPixel) => {
      const near = snapToLines(tapped, [...routeLines.values()], SNAP_PIXELS * metersPerPixel);
      choosePoint(near?.point ?? tapped, near?.routeId ?? null);
      setPicking(false);
      setPointPicker(null);
    });
  };
  const stopPicking = () => {
    setPicking(false);
    setPointPicker(null);
  };
  const done = () => {
    if (result) {
      reload();
    }
    close();
  };

  const edit = (entry: StoredTestIncident) => {
    const input = entry.input;
    setEditingId(entry.id);
    setType(input.type);
    choosePoint(input.point, input.onRoute?.routeId ?? null);
    setRoadRef(input.roadRef ?? '');
    setDirection(input.direction ?? '');
    setValidFrom(toDateTimeInput(input.validFrom));
    setValidTo(input.validTo ? toDateTimeInput(input.validTo) : '');
    setDelay(input.delayMinutes == null ? '' : String(input.delayMinutes));
    setText(input.text ?? '');
    setError(null);
    setStep('place');
  };
  const send = async (id: string, input: TestIncident, resolved: boolean) => {
    setSending(true);
    setError(null);
    try {
      const answer = await reportTestIncident(id, input, resolved);
      setEntries(storeTestIncident({ id, input, reportedAt: new Date().toISOString(), outcome: answer.outcome, resolved }));
      setResult(answer);
      setEnded(resolved);
      setStep('result');
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setSending(false);
    }
  };

  const from = localDateTimeIso(validFrom);
  const to = validTo ? localDateTimeIso(validTo) : null;
  const delayMinutes = delay.trim() ? Number(delay) : null;
  const detailsValid = !!from && (!validTo || (!!to && new Date(to) > new Date(from)))
    && (delayMinutes == null || (Number.isInteger(delayMinutes) && delayMinutes >= 0));

  const submit = () => {
    if (type && point && from) {
      void send(editingId ?? newTestIncidentId(), {
        type,
        point,
        roadRef: roadRef.trim() || null,
        direction: direction || null,
        validFrom: from,
        validTo: to,
        delayMinutes,
        text: text.trim() || null,
        onRoute: onRoute == null ? null : { tripId: trip.id, routeId: onRoute },
      }, false);
    }
  };

  const titles: Record<Step, string> = {
    type: t('testIncident.typeTitle'),
    place: t('testIncident.placeTitle'),
    details: t('testIncident.detailsTitle'),
    result: t('testIncident.resultTitle'),
  };
  const back: Record<Step, Step | null> = { type: null, place: 'type', details: 'place', result: null };
  const previous = back[step];

  return (
    <>
      <Sheet
        open={!picking}
        onClose={done}
        eyebrow={editingId ? t('testIncident.eyebrowEdit') : t('testIncident.eyebrow')}
        title={titles[step]}
        icon={<span className="va-incident-badge" aria-hidden="true"><ThunderboltOutlined /></span>}
        footer={footer()}
      >
        {previous && (
          <button type="button" className="va-text-button va-back" onClick={() => setStep(previous)}>
            {t('common.back')}
          </button>
        )}

        {step === 'type' && (
          <div className="va-form">
            <p className="va-muted">{t('testIncident.intro')}</p>
            <ul className="va-plain-list va-column va-gap" aria-label={t('testIncident.typeTitle')}>
              {INCIDENT_TYPES.map((value) => (
                <li key={value}>
                  <button
                    type="button"
                    className={`va-option ${type === value ? 'va-option-selected' : ''}`}
                    onClick={() => {
                      setType(value);
                      setStep('place');
                    }}
                  >
                    <span className="va-option-title">{t(`incident.type.${value}`)}</span>
                  </button>
                </li>
              ))}
            </ul>
            {entries.length > 0 && (
              <>
                <h3 className="va-form-section">{t('testIncident.mine')}</h3>
                <ul className="va-plain-list va-column va-gap">
                  {entries.map((entry) => (
                    <li key={entry.id} className="va-test-incident">
                      <span className="va-column va-grow">
                        <span className="va-strong">{t(`incident.type.${entry.input.type}`)}{entry.input.roadRef && ` · ${entry.input.roadRef}`}</span>
                        <span className="va-small va-muted">
                          {[formatDateTime(entry.reportedAt), entry.resolved ? t('testIncident.isEnded') : outcomeLabel(entry.outcome)].join(' · ')}
                        </span>
                      </span>
                      <span className="va-row va-gap">
                        <button type="button" className="va-text-button" disabled={sending} onClick={() => edit(entry)}>
                          {t('testIncident.change')}
                        </button>
                        {!entry.resolved && (
                          <button type="button" className="va-text-button" disabled={sending} onClick={() => void send(entry.id, entry.input, true)}>
                            {t('testIncident.end')}
                          </button>
                        )}
                        {entry.resolved && (
                          <button type="button" className="va-text-button" disabled={sending} onClick={() => setEntries(forgetTestIncident(entry.id))}>
                            {t('testIncident.forget')}
                          </button>
                        )}
                      </span>
                    </li>
                  ))}
                </ul>
                {error && <Alert type="error" showIcon message={error} />}
              </>
            )}
          </div>
        )}

        {step === 'place' && (
          <div className="va-form">
            <p className="va-muted">{t('testIncident.placeIntro')}</p>
            <button type="button" className="va-button va-button-outline va-block" onClick={startPicking}>
              <AimOutlined /> {point ? t('testIncident.pickAgain') : t('testIncident.pick')}
            </button>
            {point && (
              <p className="va-note">
                {t('testIncident.picked', {
                  lat: formatNumber(point.latitude, { maximumFractionDigits: 5 }),
                  lon: formatNumber(point.longitude, { maximumFractionDigits: 5 }),
                })}
                {' '}{onRoute != null ? t('testIncident.snapped') : t('testIncident.notSnapped')}
                {placement && <>{' '}{t('testIncident.passage', { time: formatDateTime(placement.passageStart) })}</>}
              </p>
            )}
            <div className="va-field-row">
              <div className="va-field">
                <label htmlFor="ti-road">{t('testIncident.road')}</label>
                <input id="ti-road" type="text" placeholder={t('testIncident.roadPlaceholder')} value={roadRef}
                  onChange={(event) => setRoadRef(event.target.value)} />
              </div>
              <div className="va-field">
                <label htmlFor="ti-direction">{t('testIncident.direction')}</label>
                <select id="ti-direction" value={direction} onChange={(event) => setDirection(event.target.value as TravelDirection | '')}>
                  <option value="">{t('testIncident.bothDirections')}</option>
                  {DIRECTIONS.map((value) => <option key={value} value={value}>{t(`testIncident.directions.${value}`)}</option>)}
                </select>
              </div>
            </div>
          </div>
        )}

        {step === 'details' && (
          <div className="va-form">
            <div className="va-field-row">
              <div className="va-field">
                <label htmlFor="ti-from">{t('testIncident.from')}</label>
                <input id="ti-from" type="datetime-local" value={validFrom} onChange={(event) => setValidFrom(event.target.value)} />
              </div>
              <div className="va-field">
                <label htmlFor="ti-to">{t('testIncident.to')}</label>
                <input id="ti-to" type="datetime-local" min={validFrom || undefined} value={validTo}
                  onChange={(event) => setValidTo(event.target.value)} />
              </div>
            </div>
            <div className="va-field">
              <label htmlFor="ti-delay">{t('testIncident.delay')}</label>
              <input id="ti-delay" type="number" min={0} inputMode="numeric" placeholder={t('testIncident.delayPlaceholder')}
                value={delay} onChange={(event) => setDelay(event.target.value)} />
            </div>
            <div className="va-field">
              <label htmlFor="ti-text">{t('testIncident.text')}</label>
              <textarea id="ti-text" rows={3} placeholder={t('testIncident.textPlaceholder')} value={text}
                onChange={(event) => setText(event.target.value)} />
            </div>
            {error && <Alert type="error" showIcon message={error} />}
          </div>
        )}

        {step === 'result' && result && (
          ended
            ? <div className="va-form"><p className="va-strong">{t('testIncident.endedResult')}</p></div>
            : <ResultView result={result} tripRouteIds={new Set(trip.routes.map((route) => route.id))} />
        )}
      </Sheet>

      {picking && (
        <div className="va-pick-hint" role="status">
          <span>{t('testIncident.pickHint')}</span>
          <button type="button" className="va-text-button" onClick={stopPicking}>{t('common.cancel')}</button>
        </div>
      )}
    </>
  );

  function footer() {
    if (step === 'place') {
      return (
        <button type="button" className="va-button va-button-accent va-block" disabled={!point} onClick={() => setStep('details')}>
          {t('testIncident.next')}
        </button>
      );
    }
    if (step === 'details') {
      return (
        <button type="button" className="va-button va-button-accent va-block" disabled={sending || !detailsValid} onClick={submit}>
          {sending ? t('testIncident.sending') : editingId ? t('testIncident.submitChange') : t('testIncident.submit')}
        </button>
      );
    }
    if (step === 'result') {
      return (
        <button type="button" className="va-button va-button-accent va-block" onClick={done}>
          {t('testIncident.done')}
        </button>
      );
    }
    return undefined;
  }
}

function outcomeLabel(outcome: string): string {
  return outcome === 'INCIDENT' ? t('testIncident.isActive') : t('testIncident.noIncidentShort', { outcome });
}

function ResultView({ result, tripRouteIds }: { result: TestIncidentResult; tripRouteIds: Set<number> }) {
  const incident = result.incident;
  if (!incident) {
    return (
      <div className="va-form">
        <Alert type="warning" showIcon message={t('testIncident.noIncident', { outcome: result.outcome })} />
        {result.note && <p className="va-small va-muted">{t('testIncident.note', { note: result.note })}</p>}
      </div>
    );
  }
  const roads = incident.roads.map((road) => road.ref ?? road.name).filter(Boolean).join(', ');
  const onTrip = new Set(incident.consequences.filter((c) => tripRouteIds.has(c.routeId)).map((c) => c.routeId)).size;
  return (
    <div className="va-form">
      <p className="va-strong">{t('testIncident.created')}</p>
      <p className="va-muted">
        {[t('testIncident.segments', { count: incident.segments }), roads || null,
          onTrip > 0 ? t('testIncident.affects', { count: onTrip }) : t('testIncident.notOnTrip')]
          .filter(Boolean).join(' · ')}
      </p>
      <RouteVerdict result={result} />
      {result.note && <p className="va-small va-muted">{t('testIncident.note', { note: result.note })}</p>}
    </div>
  );
}

/** Whether the route the spot was placed onto got the incident, and if not, why that is likely. */
function RouteVerdict({ result }: { result: TestIncidentResult }) {
  const { incident, placement } = result;
  if (!incident || !placement) {
    return null;
  }
  if (incident.consequences.some((c) => c.routeId === placement.routeId)) {
    return <Alert type="success" showIcon message={t('testIncident.onMap')} />;
  }
  const time = formatDateTime(placement.passageStart);
  const passageStart = new Date(placement.passageStart).getTime();
  const passageEnd = new Date(placement.passageEnd).getTime();
  const reason = passageEnd < Date.now() ? t('testIncident.routePassed', { time })
    : new Date(incident.validFrom).getTime() > passageEnd
      || (incident.validTo !== null && new Date(incident.validTo).getTime() < passageStart)
      ? t('testIncident.outsideTime', { time })
      : t('testIncident.notOnRoute');
  return <Alert type="warning" showIcon message={reason} />;
}

function errorText(failure: unknown): string {
  if (failure instanceof TestIncidentError) {
    if (failure.status === 403) return t('testIncident.forbidden');
    if (failure.status === 404) return t('testIncident.disabled');
    if (failure.status === 400 && failure.detail) return t('testIncident.invalid', { detail: failure.detail });
    if (failure.status === 422) return t('testIncident.routeGone');
  }
  return t('common.failedTryAgain');
}
