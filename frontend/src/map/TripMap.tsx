import maplibregl, { type GeoJSONSource, type LngLatLike } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useRef } from 'react';

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
  onIncidentClick: (incident: TripIncident) => void;
  /** Space the bottom sheet covers, kept free when fitting the stops. */
  bottomPadding: number;
}

type Located = Stop & { latitude: number; longitude: number };
const located = (stop: Stop | undefined): stop is Located => stop?.latitude != null && stop.longitude != null;

const WARNING_ICON =
  '<svg width="18" height="18" viewBox="0 0 24 24" fill="#ffffff" stroke="#111111" stroke-width="2.2" ' +
  'stroke-linejoin="round" aria-hidden="true"><path d="M12 3 L22 20 H2 Z"/><path d="M12 10 V14" fill="none"/>' +
  '<circle cx="12" cy="17" r="0.8" fill="#111111"/></svg>';

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
const coordinates = (line: LinePoint[]) => line.map((point) => [point.longitude, point.latitude]);

/**
 * Map of a trip with MapLibre GL: numbered stops, routes on their planned way (dashed straight line until planned),
 * affected routes highlighted, incidents as chips that open the incident dialog. While a route sheet is open, the
 * suggestions of that route are drawn instead, the selected one on top.
 */
export default function TripMap({
  stops,
  routes,
  routeLines,
  preview,
  incidentsByRoute,
  onRouteClick,
  onIncidentClick,
  bottomPadding,
}: TripMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const loaded = useRef(false);
  const markers = useRef<maplibregl.Marker[]>([]);
  const fitted = useRef<LinePoint[][] | undefined>(undefined);
  const callbacks = useRef({ onRouteClick, onIncidentClick, onPreviewSelect: preview?.onSelect });
  callbacks.current = { onRouteClick, onIncidentClick, onPreviewSelect: preview?.onSelect };

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const instance = new maplibregl.Map({
      container: container.current,
      style: MAP_STYLE,
      center: EUROPE,
      zoom: 3.5,
      attributionControl: { compact: true },
    });
    instance.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    instance.on('load', () => {
      instance.addSource('routes', { type: 'geojson', data: EMPTY });
      instance.addSource('route-preview', { type: 'geojson', data: EMPTY });
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
          if (typeof routeId === 'number') {
            callbacks.current.onRouteClick(routeId);
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
      for (const layer of ['routes-line', 'routes-unplanned', 'route-preview-other', 'route-preview-selected']) {
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
        return [{ route, from, to, affected: (incidentsByRoute.get(route.id)?.length ?? 0) > 0 }];
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
        element.setAttribute('aria-label', `Stop ${index + 1}: ${stop.name}`);
        markers.current.push(new maplibregl.Marker({ element, anchor: 'left', offset: [-14, 0] }).setLngLat([stop.longitude, stop.latitude]).addTo(instance));
      });

      lines.forEach(({ route, from, to }) => {
        const incident = incidentsByRoute.get(route.id)?.[0];
        if (!incident) {
          return;
        }
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'va-map-incident';
        chip.innerHTML = `${WARNING_ICON}<span></span>`;
        chip.querySelector('span')!.textContent = incidentTitle(incident);
        chip.setAttribute('aria-label', `Hinweis öffnen: ${incidentTitle(incident)}`);
        chip.addEventListener('click', (event) => {
          event.stopPropagation();
          callbacks.current.onIncidentClick(incident);
        });
        const planned = routeLines.get(route.id)?.geometry ?? [];
        const center = planned.length > 1 ? planned[Math.floor(planned.length / 2)] : null;
        const middle: LngLatLike = center
          ? [center.longitude, center.latitude]
          : [(from.longitude + to.longitude) / 2, (from.latitude + to.latitude) / 2];
        markers.current.push(new maplibregl.Marker({ element: chip, anchor: 'right', offset: [-12, 0] }).setLngLat(middle).addTo(instance));
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
    <div className="va-map" role="region" aria-label="Karte mit Stops und Routen">
      <div ref={container} className="va-map-canvas" />
    </div>
  );
}
