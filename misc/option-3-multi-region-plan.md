# Option 3: Multi-Region Source Blending

## Problem

Some iGPSport tiles do not map cleanly to one Geofabrik extract.

Example:
- one tile spans multiple Czech subregions
- single-region matching may fall back to `czech-republic-latest.osm.pbf`
- whole-country fallback makes processing much slower and raises RAM / IO pressure

## Goal

Keep source extracts as small as possible while still covering tile well enough to generate valid output.

## Approach

1. Decode original iGPSport filename to tile bbox.
2. Build candidate Geofabrik regions that overlap tile.
3. Prefer candidates whose parent chain resolves to same country as filename.
4. Keep normal single-region match when one specific subregion already covers tile well.
5. If best single match is only country-level fallback, try a small same-country blend:
   - choose 2 to 4 subregions
   - greedily maximize union coverage of tile bbox
   - avoid country-wide extract when subregion blend covers tile sufficiently
6. Write one `maps.csv` row per output map as before, but allow multiple source URLs in one field separated by `;`.
7. At generation time:
   - download all listed PBF/poly pairs
   - clip each source with its own polygon
   - merge clipped streams
   - run Mapsforge writer once on merged stream

## Why this option

Benefits:
- much smaller inputs than whole-country fallback
- better fit for tiles near internal region borders
- no temp intermediate files required
- keeps existing `maps.csv` shape compatible enough for manual edits

Trade-offs:
- matching still uses bounding boxes, not exact polygon math
- overlapping Geofabrik border data may still duplicate some entities before merge
- merge pipeline is more complex than single-source path

## CSV format

Columns stay same:

```csv
Original filename,OSM BPF URL,Poly URL
```

Single-source row:

```csv
CZ10002604163ER24Z00B009.map,https://download.geofabrik.de/europe/czech-republic/stredocesky-latest.osm.pbf,https://download.geofabrik.de/europe/czech-republic/stredocesky.poly
```

Multi-source row:

```csv
CZ03002604163DE24P00T00L.map,https://download.geofabrik.de/europe/czech-republic/karlovarsky-latest.osm.pbf;https://download.geofabrik.de/europe/czech-republic/plzensky-latest.osm.pbf;https://download.geofabrik.de/europe/czech-republic/ustecky-latest.osm.pbf,https://download.geofabrik.de/europe/czech-republic/karlovarsky.poly;https://download.geofabrik.de/europe/czech-republic/plzensky.poly;https://download.geofabrik.de/europe/czech-republic/ustecky.poly
```

## Selection rules

- Same-country regions beat foreign regions when available.
- Exact / near-exact specific subregion still wins.
- Multi-region blend activates mainly when:
  - best single same-country match is country-level fallback, or
  - blend improves coverage materially over single-region match

## Runtime behavior

- `script.ps1` and `script.sh` now treat `;` as source separator inside URL columns.
- Each PBF is filtered by its matching `.poly`.
- Filtered streams are merged directly in Osmosis before `mapfile-writer`.
- RAM auto-tuning still applies, but now based on sum of source PBF sizes for that row.
