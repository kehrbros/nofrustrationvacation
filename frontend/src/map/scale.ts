/** MapLibre tiles are 512 px: metres per pixel at zoom 0 on the equator. */
const METERS_PER_PIXEL_Z0 = 40_075_016.686 / 512;

/** Metres one screen pixel covers at that latitude and zoom of the map. */
export function metersPerPixel(latitude: number, zoom: number): number {
  return (METERS_PER_PIXEL_Z0 * Math.cos((latitude * Math.PI) / 180)) / 2 ** zoom;
}
