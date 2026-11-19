# Base map data

Country borders and lakes drawn by the Coverage tab come from [Natural Earth](https://www.naturalearthdata.com/),
which is in the **public domain** (no permission or attribution required; see
https://www.naturalearthdata.com/about/terms-of-use/).

| File | Source (Natural Earth v5.1.2) |
|------|-------------------------------|
| `ne_110m_admin_0_countries.json.gz` | `geojson/ne_110m_admin_0_countries.geojson` |
| `ne_50m_admin_0_countries.json.gz` | `geojson/ne_50m_admin_0_countries.geojson` |
| `ne_50m_lakes.json.gz` | `geojson/ne_50m_lakes.geojson` |

Downloaded from https://github.com/nvkelso/natural-earth-vector. To keep the app small, only the
`name` (`NAME_EN`), `label_x`/`label_y` (`LABEL_X`/`LABEL_Y`) and `min_label` (`MIN_LABEL`) properties
are kept, coordinates are rounded to 4 decimals, and the files are gzip-compressed.
