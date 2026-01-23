# IGPSPORT Map Updater

A tool to generate custom map files for IGPSPORT cycling computers from OpenStreetMap data. This project downloads OSM PBF files, filters them based on polygon boundaries, transforms unsupported tags, and generates Mapsforge map files with the specific naming convention required by IGPSPORT GPS devices.

## Supported Products

- BiNavi
- iGS800
- iGS630
- iGS630S
- BSC300T
- BSC300

Official support/download page: https://www.igpsport.com/en/support/product

## Requirements

- **Java 17** or higher
- **Internet connection** (for downloading OSM data and dependencies)
- **Disk space**: Several GB depending on the size of the regions being processed

### Platform-Specific Requirements

#### Windows
- PowerShell 5.0 or higher (included in Windows 10/11)
- Windows 7 or higher

#### Unix/Linux/macOS
- Bash shell
- `curl` (for downloading files)
- `unzip` (for extracting archives)
- `bc` (for mathematical calculations)

## Important Notes

### Before You Start

⚠️ **Use At Your Own Risk**: Do it at your own risk; I am not responsible for broken devices.

⚠️ **Backup Your Maps**: Before using the new maps, make sure to backup your existing maps from your IGPSPORT cycle computer. Store them in a safe location in case you need to revert.

⚠️ **Free Up Space**: Remove old maps from your cycle computer before transferring new ones. The new maps are significantly larger due to enhanced tags and more detailed information, so you'll need extra storage space.

⚠️ **Processing Time**: The map conversion process takes several hours to complete, depending on the size of the regions and your computer's performance. Plan accordingly and let the script run without interruption.

### Map Size Changes

The generated maps are **larger than original IGPSPORT maps** because they include:
- More detailed road networks
- Additional map features (waterways, natural features)
- Enhanced tagging information for better routing
- Extended geographic data

### Tag Configuration

This project uses two XML configuration files to control which OpenStreetMap tags are included in the generated maps:

#### Supported Tags ([tag-igpsport.xml](tag-igpsport.xml))

The following **21 tags** are directly supported by IGPSPORT devices:

| Category | Tags | Zoom Level |
|----------|------|------------|
| **Major Roads** | primary, primary_link, secondary, secondary_link, trunk, trunk_link, tertiary, tertiary_link | 13 |
| **Minor Roads** | cycleway, living_street, pedestrian, residential, road, service, track, unclassified | 14 |
| **Natural** | coastline, water | 13 |
| **Waterways** | canal, dam, drain, river, stream | 14 |

#### Tag Transformations ([tag-igpsport-transform.xml](tag-igpsport-transform.xml))

The following **12 transformations** convert unsupported OSM tags to device-compatible equivalents:

| Unsupported Tag | Transformed To | Description |
|-----------------|----------------|-------------|
| `highway=motorway` | `highway=trunk` | Major highways |
| `highway=motorway_link` | `highway=trunk_link` | Highway ramps |
| `highway=footway` | `highway=cycleway` | Pedestrian paths |
| `highway=path` | `highway=cycleway` | Generic paths |
| `highway=bridleway` | `highway=cycleway` | Horse trails |
| `highway=sidewalk` | `highway=cycleway` | Sidewalks |
| `highway=steps` | `highway=pedestrian` | Stairs |
| `highway=byway` | `highway=track` | Rural byways |
| `highway=bus_guideway` | `highway=road` | Guided bus routes |
| `highway=construction` | `highway=road` | Roads under construction |
| `highway=raceway` | `highway=road` | Racing circuits |
| `highway=services` | `highway=service` | Service areas |

This means the maps effectively support **33 highway types** (21 native + 12 transformed).

See the [Mapsforge tag-mapping reference](https://github.com/mapsforge/mapsforge/blob/master/mapsforge-map-writer/src/main/config/tag-mapping.xml) for all available OpenStreetMap tags.

## How to Run

### Windows

Run the PowerShell script:

```powershell
.\script.ps1
```

Or right-click `script.ps1` and select "Run with PowerShell"

### Unix/Linux/macOS

Make the script executable and run it:

```bash
chmod +x script.sh
./script.sh
```

## What the Script Does

1. **Downloads Osmosis** (if not present) - OpenStreetMap data processing tool (v0.49.2)
2. **Downloads Mapsforge Writer Plugin** (v0.27.0) - Converts OSM data to Mapsforge format
3. **Reads maps.csv** - Configuration file with region definitions
4. **Downloads OSM PBF files** - Raw OpenStreetMap data from Geofabrik
5. **Downloads Polygon files** - Geographic boundary definitions
6. **Transforms tags** - Converts unsupported OSM tags to device-compatible equivalents
7. **Generates maps** - Creates Mapsforge `.map` files with proper zoom levels
8. **Renames output** - Calculates GEOCODE and applies IGPSPORT filename convention

## CSV File Structure

The `maps.csv` file defines which regions to process. It has three columns:

| Column | Description | Example |
|--------|-------------|---------|
| **Original filename** | The target IGPSPORT map filename format | `BR01002303102B83FO00N00E.map` |
| **OSM BPF URL** | URL to download the OpenStreetMap PBF file | `https://download.geofabrik.de/south-america/brazil-latest.osm.pbf` |
| **Poly URL** | URL to download the polygon boundary file | `https://download.openstreetmap.fr/polygons/south-america/brazil/central-west/distrito-federal.poly` |

### Example CSV Content

```csv
Original filename,OSM BPF URL,Poly URL
BR01002303102B83FO00N00E.map,https://download.geofabrik.de/south-america/brazil-latest.osm.pbf,https://download.openstreetmap.fr/polygons/south-america/brazil/central-west/distrito-federal.poly
BR02002303102833DN04O04Q.map,https://download.geofabrik.de/south-america/brazil-latest.osm.pbf,https://download.openstreetmap.fr/polygons/south-america/brazil/central-west/goias.poly
```

### Where to Find Resources

- **OSM PBF files**: [Geofabrik Downloads](https://download.geofabrik.de/)
- **Polygon files**: [OpenStreetMap Polygons](https://download.openstreetmap.fr/polygons/)

## Directory Structure

```
igpsport-map-updater/
├── script.sh                    # Unix/Linux/macOS execution script
├── script.ps1                   # Windows PowerShell execution script
├── maps.csv                     # Configuration file with map definitions
├── tag-igpsport.xml             # Tag configuration for Mapsforge writer
├── tag-igpsport-transform.xml   # Tag transformation rules
├── extract_tags.py              # Utility to extract tags from .map files
├── download/                    # Downloaded OSM PBF and polygon files
│   ├── *.osm.pbf
│   └── *.poly
├── output/                      # Generated map files (final output)
│   └── *.map
├── backup/                      # Store original IGPSPORT maps here
├── tmp/                         # Temporary files during processing
├── misc/                        # Documentation and diagrams
│   ├── filename-structure.svg
│   ├── tile-grid-concept.svg
│   └── compare-2023-to-2026.jpg
└── osmosis-0.49.2/              # Osmosis tool (auto-downloaded)
    ├── bin/
    ├── lib/
    └── script/
```

### Directory Descriptions

- **download/**: Stores downloaded OSM PBF files and polygon boundary files. Files are cached to avoid re-downloading.
- **output/**: Contains the final generated `.map` files with IGPSPORT-compatible filenames.
- **backup/**: Recommended location to store your original IGPSPORT maps before replacing them.
- **tmp/**: Temporary directory used by Osmosis during processing (can be deleted after completion).
- **misc/**: Contains SVG diagrams explaining the filename structure and tile grid concepts.
- **osmosis-0.49.2/**: Automatically downloaded and extracted Osmosis tool with Mapsforge plugin.

## IGPSPORT Filename Structure

The generated map files follow a specific naming convention required by IGPSPORT devices:

### Format

```
[CC][RRRR][YYMMDD][GEOCODE].map
```

### Components

| Component | Length | Description | Example |
|-----------|--------|-------------|---------|
| **CC** | 2 chars | Country code | `BR`, `PL`, `US` |
| **RRRR** | 4 digits | Region/Product code | `0100`, `0200` |
| **YYMMDD** | 6 digits | Date (Year, Month, Day) | `250317` = March 17, 2025 |
| **GEOCODE** | 12 chars | Geographic boundary encoding | `2B83FO00N00E` |

### GEOCODE Breakdown (12 characters, Base36 encoding)

The GEOCODE consists of 4 parts, each 3 characters in Base36:

1. **MIN_LON** (XXX): Western boundary - minimum longitude as tile X coordinate at zoom 13
2. **MAX_LAT** (YYY): Northern boundary - maximum latitude as tile Y coordinate at zoom 13
3. **LON_SPAN** (WWW): Width in tiles - 1 (horizontal span)
4. **LAT_SPAN** (HHH): Height in tiles - 1 (vertical span)

![Tile Grid Concept](misc/tile-grid-concept.svg)

### Example

**Filename**: `BR01002303102B83FO00N00E.map`

- **BR**: Brazil
- **0100**: Region code 0100
- **230310**: March 10, 2023
- **2B8**: MIN_LON (tile X at zoom 13)
- **3FO**: MAX_LAT (tile Y at zoom 13)
- **00N**: LON_SPAN (width in tiles)
- **00E**: LAT_SPAN (height in tiles)

For a visual representation of the filename structure, see below:

![IGPSPORT Filename Structure](misc/filename-structure.svg)

## Viewing and Comparing Maps

### Cruiser - Map Viewer

[Cruiser](https://wiki.openstreetmap.org/wiki/Cruiser) is a cross-platform map viewer that supports Mapsforge map files, making it ideal for viewing and comparing the generated maps.

#### Features
- View `.map` files generated by this tool
- Compare different map versions
- Test maps before deploying to IGPSPORT devices
- Supports multiple map formats including Mapsforge

#### Download
Visit the [Cruiser Wiki page](https://wiki.openstreetmap.org/wiki/Cruiser) for download links and documentation.

#### Usage
1. Open Cruiser
2. Load your generated `.map` file from the `output/` directory
3. Compare with other map versions or sources

#### Comparison Example

Below is a comparison showing the difference between the original IGPSPORT map (left) and the enhanced map (right) with additional features and details:

![Map Comparison - Original vs Enhanced](misc/compare-2023-to-2026.jpg)

*Left: Original map (2023) | Right: Enhanced map (2026

## Troubleshooting

### Java Not Found
Ensure Java 17 or higher is installed:
```bash
java -version
```

### Out of Memory Errors
Increase Java heap size by editing the `JAVA_OPTS` variable in the script:
```bash
export JAVA_OPTS="-Xms2g -Xmx16g -Djava.io.tmpdir=$TMP_DIR"
```

### Download Failures
- Check your internet connection
- Verify the URLs in `maps.csv` are accessible
- Some regions may have updated URLs on Geofabrik

### Permission Denied (Unix/Linux)
Make the script executable:
```bash
chmod +x script.sh
```

## Technical Details

### Processing Pipeline

1. **Read PBF** → Load OpenStreetMap binary data
2. **Apply Polygon** → Filter data to geographic boundary
3. **Tag Filter** → Remove unwanted features, keep roads, waterways, landuse
4. **Used Node** → Keep only referenced nodes
5. **Merge** → Combine filtered data
6. **Mapfile Writer** → Generate Mapsforge `.map` file
7. **Rename** → Calculate GEOCODE and apply IGPSPORT filename

### Map Features Included

The script filters OSM data to include:
- **Roads**: highways, paths, tracks (with tag transformation for unsupported types)
- **Waterways**: rivers, streams, canals, dams, drains
- **Natural features**: water bodies, coastlines

All other features (buildings, POIs, amenities, etc.) are filtered out to reduce file size and focus on navigation.

## Utilities

### Map Tag Extractor (extract_tags_map.py)

A Python utility to extract and analyze tags from Mapsforge `.map` files. Useful for:
- Inspecting what tags are embedded in existing maps
- Comparing tags between different map versions
- Debugging tag configuration issues

**Usage:**
```bash
# Single file
python extract_tags_map.py output/map.map

# Single file with output
python extract_tags_map.py output/map.map tags.txt

# Process all files in folder
python extract_tags_map.py backup/

# Process folder with output
python extract_tags_map.py backup/ tags_output/
```

### PBF Tag Extractor (extract_tags_pbf.py)

A Python utility to extract and analyze tags from raw OSM PBF files before processing. This tool helps you understand what tags are available in the source data and plan your tag filtering/transformation strategy.

**Features:**
- Extract tags from nodes, ways, and relations separately
- Display tag frequency statistics
- Export results in multiple formats (text, JSON, CSV)
- Process individual files or entire folders
- Filter by minimum occurrence count
- Requires either `pyosmium` (faster) or `osmium-tool`

**Installation:**
```bash
# Option 1: Install pyosmium (recommended - faster)
pip install osmium

# Option 2: Install osmium-tool
# Ubuntu/Debian
sudo apt-get install osmium-tool

# Fedora
sudo dnf install osmium-tool

# macOS
brew install osmium-tool
```

**Usage:**
```bash
# Single file - display tags in terminal
python extract_tags_pbf.py download/brazil-latest.osm.pbf

# Single file - export to text file
python extract_tags_pbf.py download/sao-paulo.pbf -o tags.txt

# Single file - export to JSON
python extract_tags_pbf.py download/sao-paulo.pbf -o tags.json -f json

# Single file - export to CSV
python extract_tags_pbf.py download/sao-paulo.pbf -o tags.csv -f csv

# Process all .pbf files in folder
python extract_tags_pbf.py download/

# Process folder and export each file
python extract_tags_pbf.py download/ -o output_tags/

# Process with filters
python extract_tags_pbf.py download/ -o output/ -f json -m 10 -d 100
```

**Options:**
- `-o, --output`: Output file or folder for extracted tags
- `-f, --format`: Output format - `text` (default), `json`, or `csv`
- `-m, --min-count`: Minimum occurrence count to include (default: 1)
- `-d, --display`: Maximum tags to display in terminal (default: 50)

**Use Cases:**
- Analyze tag distribution in source OSM data
- Identify which highway types are present in your region
- Determine what tags need transformation for IGPSPORT compatibility
- Compare tag usage across different regions
- Plan custom tag filtering strategies

## License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](LICENSE) file for details.

### Dependencies and Data
- **Osmosis**: LGPL ([GitHub](https://github.com/openstreetmap/osmosis))
- **Mapsforge**: LGPL ([GitHub](https://github.com/mapsforge/mapsforge))
- **OpenStreetMap Data**: ODbL ([License](https://www.openstreetmap.org/copyright))

## References

- [Reddit thread](https://www.reddit.com/r/cycling/comments/1khm2ou/newcustom_maps_on_igpsport_bsc300t_630s) shared by [u/povlhp](https://www.reddit.com/user/povlhp/)
- [Original project](https://github.com/tm-cms/MapsforgeMapName) by [tm-cms](https://github.com/tm-cms)
- [OpenStreetMap](https://www.openstreetmap.org/)
- [OpenStreetMap France](https://download.openstreetmap.fr/) - Polygon files
- [Geofabrik Downloads](https://download.geofabrik.de/) - OSM PBF files
- [Osmosis Documentation](https://wiki.openstreetmap.org/wiki/Osmosis)
- [Mapsforge](https://github.com/mapsforge/mapsforge)
- [Cruiser Map Viewer](https://wiki.openstreetmap.org/wiki/Cruiser)
