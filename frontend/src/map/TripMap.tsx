import maplibregl, { type GeoJSONSource, type LngLatLike } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useRef } from 'react';

import { t } from '../i18n';

import { incidentTitle } from '../trips/incidentText';
import type { LinePoint, Route, RouteLine, Stop, TripIncident } from '../trips/model';
import type { RoutePreview } from '../trips/tripContext';

/** OpenStreetMap based vector tiles without API key, see Docs/architecture.md. */
const MAP_STYLE = import.meta.env.VITE_MAP_STYLE_URL ?? 'https://tiles.openfreemap.org/styles/positron';
const EUROPE: LngLatLike = [10.5, 48];

export interface TripMapProps {
  stops: Stop[];
  routes: Route[];
  /** Planned ways by route id; routes without one are drawn as a dashed straight line. */
  routeLines: Map<number, RouteLine>;
  /** Suggestions of one route to choose from, drawn instead of its planned way. */
  preview: RoutePreview | null;
  incidentsByRoute: Map<number, TripIncident[]>;
  onRouteClick: (routeId: number) => void;
  /** A consequence was tapped, its chip or its stretch: opens the TripAlternatives. */
  onConsequenceClick: (consequence: TripIncident) => void;
  /** Space the bottom sheet covers, kept free when fitting the stops. */
  bottomPadding: number;
  /** Any tap on the map itself (not on a chip or a control), e.g. to collapse the expanded trip sheet. */
  onMapClick?: () => void;
}

type Located = Stop & { latitude: number; longitude: number };
const located = (stop: Stop | undefined): stop is Located => stop?.latitude != null && stop.longitude != null;

const WARNING_ICON =
  '<svg width="18" height="18" viewBox="0 0 24 24" fill="#ffffff" stroke="#111111" stroke-width="2.2" ' +
  'stroke-linejoin="round" aria-hidden="true"><path d="M12 3 L22 20 H2 Z"/><path d="M12 10 V14" fill="none"/>' +
  '<circle cx="12" cy="17" r="0.8" fill="#111111"/></svg>';

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
const coordinates = (line: LinePoint[]) => line.map((point) => [point.longitude, point.latitude]);
const consequenceKey = (consequence: TripIncident) => `${consequence.routeId}:${consequence.incidentId}`;
/** Point halfway along the line by length, so the chip sits on the stretch even with uneven points. */
function middleOf(line: LinePoint[]): LinePoint {
  const lengths = line.slice(1).map((point, index) => Math.hypot(point.longitude - line[index].longitude, point.latitude - line[index].latitude));
  let rest = lengths.reduce((sum, length) => sum + length, 0) / 2;
  for (let index = 0; index < lengths.length; index++) {
    if (rest <= lengths[index] && lengths[index] > 0) {
      const share = rest / lengths[index];
      const [a, b] = [line[index], line[index + 1]];
      return { latitude: a.latitude + (b.latitude - a.latitude) * share, longitude: a.longitude + (b.longitude - a.longitude) * share };
    }
    rest -= lengths[index];
  }
  return line[Math.floor(line.length / 2)];
}
/** Chip look per category: too late in ink, delayed in the accent, no impact plain. */
const chipClass = (consequence: TripIncident) =>
  `va-map-incident va-map-incident-${(consequence.category ?? 'open').toLowerCase().replace('_', '-')}`;

/**
 * Map of a trip with MapLibre GL: numbered stops, routes on their planned way (dashed straight line until planned),
 * consequences as the stretch of the route their incident covers plus a chip; tapping either opens the
 * TripAlternatives. A route whose consequences have no stretch (yet) is highlighted as a whole. While a route sheet
 * is open, the suggestions of that route are drawn instead, the selected one on top.
 */
export default function TripMap({
  stops,
  routes,
  routeLines,
  preview,
  incidentsByRoute,
  onRouteClick,
  onConsequenceClick,
  bottomPadding,
  onMapClick,
}: TripMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const loaded = useRef(false);
  const markers = useRef<maplibregl.Marker[]>([]);
  const fitted = useRef<LinePoint[][] | undefined>(undefined);
  const consequences = useRef(new Map<string, TripIncident>());
  const callbacks = useRef({ onRouteClick, onConsequenceClick, onPreviewSelect: preview?.onSelect, onMapClick });
  callbacks.current = { onRouteClick, onConsequenceClick, onPreviewSelect: preview?.onSelect, onMapClick };

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const instance = new maplibregl.Map({
      container: container.current,
      style: MAP_STYLE,
      center: EUROPE,
      zoom: 3.5,
      attributionControl: false,
    });
    instance.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    // top-left: the trip sheet covers the bottom corners on phones
    instance.addControl(new maplibregl.AttributionControl({ compact: false }), 'top-left');
    instance.on('click', () => callbacks.current.onMapClick?.());
    instance.on('load', () => {
      instance.addSource('routes', { type: 'geojson', data: EMPTY });
      instance.addSource('route-preview', { type: 'geojson', data: EMPTY });
      instance.addSource('consequences', { type: 'geojson', data: EMPTY });
      instance.addLayer({
        id: 'routes-affected',
        type: 'line',
        source: 'routes',
        filter: ['==', ['get', 'affected'], true],
        layout: { 'line-cap': 'round' },
        paint: { 'line-color': '#f2b705', 'line-width': 11 },
      });
      instance.addLayer({
        id: 'routes-line',
        type: 'line',
        source: 'routes',
        filter: ['==', ['get', 'planned'], true],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#111111', 'line-width': 4 },
      });
      instance.addLayer({
        id: 'routes-unplanned',
        type: 'line',
        source: 'routes',
        filter: ['==', ['get', 'planned'], false],
        paint: { 'line-color': '#111111', 'line-width': 3, 'line-dasharray': [2, 2] },
      });
      // the stretch an incident covers, on top of the route: ink casing, filling by category
      instance.addLayer({
        id: 'consequences-casing',
        type: 'line',
        source: 'consequences',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#111111', 'line-width': 12 },
      });
      instance.addLayer({
        id: 'consequences-line',
        type: 'line',
        source: 'consequences',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': ['match', ['get', 'category'], 'NO_IMPACT', '#ffffff', '#f2b705'],
          'line-width': 7,
        },
      });
      instance.addLayer({
        id: 'consequences-too-late',
        type: 'line',
        source: 'consequences',
        filter: ['==', ['get', 'category'], 'TOO_LATE'],
        paint: { 'line-color': '#111111', 'line-width': 3, 'line-dasharray': [1, 1.5] },
      });
      instance.addLayer({
        id: 'route-preview-other',
        type: 'line',
        source: 'route-preview',
        filter: ['==', ['get', 'selected'], false],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#8a8a8a', 'line-width': 5 },
      });
      instance.addLayer({
        id: 'route-preview-selected',
        type: 'line',
        source: 'route-preview',
        filter: ['==', ['get', 'selected'], true],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#111111', 'line-width': 6 },
      });
      for (const layer of ['routes-line', 'routes-unplanned']) {
        instance.on('click', layer, (event) => {
          const routeId = event.features?.[0]?.properties?.routeId;
          const onConsequence = instance.queryRenderedFeatures(event.point, { layers: ['consequences-casing'] }).length > 0;
          if (typeof routeId === 'number' && !onConsequence) {
            callbacks.current.onRouteClick(routeId);
          }
        });
      }
      for (const layer of ['consequences-casing', 'consequences-line']) {
        instance.on('click', layer, (event) => {
          const consequence = consequences.current.get(String(event.features?.[0]?.properties?.key));
          if (consequence) {
            // the route line below would open the route sheet as well
            event.originalEvent.stopPropagation();
            event.preventDefault();
            callbacks.current.onConsequenceClick(consequence);
          }
        });
      }
      for (const layer of ['route-preview-other', 'route-preview-selected']) {
        instance.on('click', layer, (event) => {
          const index = event.features?.[0]?.properties?.index;
          if (typeof index === 'number') {
            callbacks.current.onPreviewSelect?.(index);
          }
        });
      }
      for (const layer of ['routes-line', 'routes-unplanned', 'consequences-casing', 'route-preview-other', 'route-preview-selected']) {
        instance.on('mouseenter', layer, () => (instance.getCanvas().style.cursor = 'pointer'));
        instance.on('mouseleave', layer, () => (instance.getCanvas().style.cursor = ''));
      }
      loaded.current = true;
      instance.fire('va:data');
    });
    map.current = instance;
    return () => {
      loaded.current = false;
      instance.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    const instance = map.current;
    if (!instance) {
      return;
    }
    const byId = new Map(stops.map((stop) => [stop.id, stop]));
    const draw = () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];

      const lines = routes.flatMap((route) => {
        const from = byId.get(route.fromStopId);
        const to = byId.get(route.toStopId);
        if (!located(from) || !located(to)) {
          return [];
        }
        // highlighted as a whole only while none of its consequences has a stretch to draw
        const onRoute = incidentsByRoute.get(route.id) ?? [];
        return [{ route, from, to, affected: onRoute.length > 0 && onRoute.every((c) => c.geometry.length < 2) }];
      });
      (instance.getSource('routes') as GeoJSONSource | undefined)?.setData({
        type: 'FeatureCollection',
        features: lines.map(({ route, from, to, affected }) => {
          const planned = routeLines.get(route.id)?.geometry ?? [];
          return {
            type: 'Feature',
            properties: { routeId: route.id, affected, planned: planned.length > 1 },
            geometry: {
              type: 'LineString',
              coordinates:
                planned.length > 1
                  ? coordinates(planned)
                  : [
                      [from.longitude, from.latitude],
                      [to.longitude, to.latitude],
                    ],
            },
          };
        }),
      });

      stops.filter(located).forEach((stop, index) => {
        const element = document.createElement('div');
        element.className = 'va-map-stop';
        element.innerHTML = `<span class="va-map-stop-number">${index + 1}</span><span class="va-map-stop-name"></span>`;
        element.querySelector('.va-map-stop-name')!.textContent = stop.name;
        element.setAttribute('aria-label', t('map.stop', { position: index + 1, name: stop.name }));
        markers.current.push(new maplibregl.Marker({ element, anchor: 'left', offset: [-14, 0] }).setLngLat([stop.longitude, stop.latitude]).addTo(instance));
      });

      const all = [...incidentsByRoute.values()].flat();
      consequences.current = new Map(all.map((consequence) => [consequenceKey(consequence), consequence]));
      const stretches = all.filter((consequence) => consequence.geometry.length > 1);
      (instance.getSource('consequences') as GeoJSONSource | undefined)?.setData({
        type: 'FeatureCollection',
        features: stretches.map((consequence) => ({
          type: 'Feature',
          properties: { key: consequenceKey(consequence), routeId: consequence.routeId, category: consequence.category ?? 'OPEN' },
          geometry: { type: 'LineString', coordinates: coordinates(consequence.geometry) },
        })),
      });

      const addChip = (consequence: TripIncident, at: LngLatLike) => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = chipClass(consequence);
        chip.innerHTML = `${WARNING_ICON}<span></span>`;
        chip.querySelector('span')!.textContent = incidentTitle(consequence);
        chip.setAttribute('aria-label', t('map.alternatives', { title: incidentTitle(consequence) }));
        chip.addEventListener('click', (event) => {
          event.stopPropagation();
          callbacks.current.onConsequenceClick(consequence);
        });
        markers.current.push(new maplibregl.Marker({ element: chip, anchor: 'right', offset: [-12, 0] }).setLngLat(at).addTo(instance));
      };
      // a chip at each stretch; consequences without one share a chip in the middle of their route
      stretches.forEach((consequence) => {
        const middle = middleOf(consequence.geometry);
        addChip(consequence, [middle.longitude, middle.latitude]);
      });
      lines.forEach(({ route, from, to }) => {
        const consequence = incidentsByRoute.get(route.id)?.find((candidate) => candidate.geometry.length < 2);
        if (!consequence) {
          return;
        }
        const planned = routeLines.get(route.id)?.geometry ?? [];
        const center = planned.length > 1 ? middleOf(planned) : null;
        addChip(
          consequence,
          center ? [center.longitude, center.latitude] : [(from.longitude + to.longitude) / 2, (from.latitude + to.latitude) / 2],
        );
      });

      const points = stops.filter(located);
      if (points.length === 1) {
        instance.jumpTo({ center: [points[0].longitude, points[0].latitude], zoom: 9 });
      } else if (points.length > 1) {
        const bounds = new maplibregl.LngLatBounds();
        points.forEach((stop) => bounds.extend([stop.longitude, stop.latitude]));
        instance.fitBounds(bounds, { padding: { top: 60, left: 60, right: 60, bottom: bottomPadding + 40 }, duration: 0, maxZoom: 11 });
      }
    };
    if (loaded.current) {
      draw();
    } else {
      instance.once('va:data', draw);
    }
    return () => {
      instance.off('va:data', draw);
    };
  }, [stops, routes, routeLines, incidentsByRoute, bottomPadding]);

  // suggestions of one route: drawn instead of its planned way, the map zooms to them above the open sheet
  const previewRouteId = preview?.routeId ?? null;
  const previewLines = preview?.lines;
  const previewSelected = preview?.selected ?? 0;
  useEffect(() => {
    const instance = map.current;
    if (!instance) {
      return;
    }
    const draw = () => {
      const routeFilter: maplibregl.FilterSpecification =
        previewRouteId === null ? ['literal', true] : ['!=', ['get', 'routeId'], previewRouteId];
      instance.setFilter('routes-line', ['all', ['==', ['get', 'planned'], true], routeFilter]);
      instance.setFilter('routes-unplanned', ['all', ['==', ['get', 'planned'], false], routeFilter]);
      instance.setFilter('routes-affected', ['all', ['==', ['get', 'affected'], true], routeFilter]);
      instance.setFilter('consequences-casing', routeFilter);
      instance.setFilter('consequences-line', routeFilter);
      instance.setFilter('consequences-too-late', ['all', ['==', ['get', 'category'], 'TOO_LATE'], routeFilter]);
      const lines = (previewLines ?? []).filter((line) => line.length > 1);
      (instance.getSource('route-preview') as GeoJSONSource | undefined)?.setData({
        type: 'FeatureCollection',
        features: lines.map((line, index) => ({
          type: 'Feature',
          properties: { index, selected: index === previewSelected },
          geometry: { type: 'LineString', coordinates: coordinates(line) },
        })),
      });
      if (lines.length > 0 && fitted.current !== previewLines) {
        fitted.current = previewLines;
        const bounds = new maplibregl.LngLatBounds();
        lines.flat().forEach((point) => bounds.extend([point.longitude, point.latitude]));
        const sheet = Math.round(window.innerHeight * 0.6);
        instance.fitBounds(bounds, { padding: { top: 70, left: 40, right: 40, bottom: sheet + 20 }, duration: 300, maxZoom: 13 });
      }
    };
    if (loaded.current) {
      draw();
    } else {
      instance.once('va:data', draw);
    }
    return () => {
      instance.off('va:data', draw);
    };
  }, [previewRouteId, previewLines, previewSelected]);

  // MapLibre sets position: relative on its container, so the container fills an absolutely positioned frame
  return (
    <div className="va-map" role="region" aria-label={t('map.label')}>
      <div ref={container} className="va-map-canvas" />
    </div>
  );
}
