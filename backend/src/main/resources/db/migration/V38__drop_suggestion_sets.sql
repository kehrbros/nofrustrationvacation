-- Route suggestions are computed per request and no longer stored, only the chosen one is stored as waypoints
-- (Docs/recommendations.md#auswahl-speichern). waypoint.origin from V29 stays.
DROP TABLE route_option_conflict;
DROP TABLE route_option_point;
DROP TABLE route_option_label;
DROP TABLE route_option;
DROP TABLE suggestion_set;
