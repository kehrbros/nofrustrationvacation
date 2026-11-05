-- Ramp or link road (OSM highway=*_link), filled by the next import of each region, see Docs/network.md
ALTER TABLE road_segment ADD COLUMN link BOOLEAN DEFAULT FALSE NOT NULL;
