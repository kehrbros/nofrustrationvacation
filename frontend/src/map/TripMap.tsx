import maplibregl, { type GeoJSONSource, type LngLatLike } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useRef } from 'react';

import { incidentTitle } from '../trips/incidentText';
import type { Route, Stop, TripIncident } from '../trips/model';

/** OpenStreetMap based vector tiles without API key, see Docs/architecture.md. */
const MAP_STYLE = import.meta.env.VITE_MAP_STYLE_URL ?? 'https://tiles.openfreemap.org/styles/positron';
const EUROPE: LngLatLike = [10.5, 48];

export interface TripMapProps {
  stops: Stop[];
  routes: Route[];
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

/**
 * Map of a trip with MapLibre GL: numbered stops, routes as straight lines between them (the planned way comes with
 * the routing service), affected routes highlighted, incidents as chips that open the incident dialog.
 */
export default function TripMap({ stops, routes, incidentsByRoute, onRouteClick, onIncidentClick, bottomPadding }: TripMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const loaded = useRef(false);
  const markers = useRef<maplibregl.Marker[]>([]);
  const callbacks = useRef({ onRouteClick, onIncidentClick });
  callbacks.current = { onRouteClick, onIncidentClick };

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
      instance.addSource('routes', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
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
        layout: { 'line-cap': 'round' },
        paint: { 'line-color': '#111111', 'line-width': 4 },
      });
      instance.on('click', 'routes-line', (event) => {
        const routeId = event.features?.[0]?.properties?.routeId;
        if (typeof routeId === 'number') {
          callbacks.current.onRouteClick(routeId);
        }
      });
      instance.on('mouseenter', 'routes-line', () => (instance.getCanvas().style.cursor = 'pointer'));
      instance.on('mouseleave', 'routes-line', () => (instance.getCanvas().style.cursor = ''));
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
        features: lines.map(({ route, from, to, affected }) => ({
          type: 'Feature',
          properties: { routeId: route.id, affected },
          geometry: {
            type: 'LineString',
            coordinates: [
              [from.longitude, from.latitude],
              [to.longitude, to.latitude],
            ],
          },
        })),
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
        const middle: LngLatLike = [(from.longitude + to.longitude) / 2, (from.latitude + to.latitude) / 2];
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
  }, [stops, routes, incidentsByRoute, bottomPadding]);

  // MapLibre sets position: relative on its container, so the container fills an absolutely positioned frame
  return (
    <div className="va-map" role="region" aria-label="Karte mit Stops und Routen">
      <div ref={container} className="va-map-canvas" />
    </div>
  );
}
