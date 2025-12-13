# IGPSport BSC300/BSC300T/IGS630s and XOSS Nav

### Quick Conversion Using `convert.ps1` ###

Instead of manual conversion, you can use the automated PowerShell script:

1. **Download a `.pbf` file** from [Geofabrik](https://download.geofabrik.de/) or [BBBike Extract](https://extract.bbbike.org/)
2. **Place the `.pbf` file(s)** in the main project folder (where `convert.ps1` is located)
3. **Run `convert.ps1`** in PowerShell
4. **Enter 2-character country code** when prompted (e.g., `PL`, `DE`, `FR`)
5. **Done!** The script generates properly named `.map` files ready for your IGPSPORT device

The script automatically:
- Detects all `.pbf` files in the folder
- Processes each file through Osmosis
- Reads map metadata (bounding box)
- Generates IGPSPORT-compatible file names

### Information about maps for IGPSport cycling computers ###

Map files for other regions can be downloaded from the official IGPSport website: [IGPSport Support](https://support.en.igpsport.com/product/support/all)

Unfortunately, as is often the case with Chinese manufacturers, the maps were generated on March 7, 2023. Therefore, similar to XOSS, there's an idea to generate maps ourselves.

#### Polish Voivodeship Codes ####

| Code | Polish Name | English Name |
|------|-------------|--------------|
| PL01 | Dolnośląskie | Lower Silesia |
| PL02 | Kujawsko-Pomorskie | Kuyavian-Pomeranian |
| PL03 | Łódzkie | Łódź Voivodeship |
| PL04 | Lubelskie | Lublin Province |
| PL05 | Lubuskie | Lubusz Voivodeship |
| PL06 | Małopolskie | Lesser Poland |
| PL07 | Mazowieckie | Masovia |
| PL08 | Opolskie | Opole Province |
| PL09 | Podkarpackie | Subcarpathian |
| PL10 | Podlaskie | Podlachia |
| PL11 | Pomorskie | Pomeranian |
| PL12 | Śląskie | Silesia |
| PL13 | Świętokrzyskie | Holy Cross |
| PL14 | Warmińsko-Mazurskie | Warmia-Masuria |
| PL15 | Wielkopolskie | Greater Poland |
| PL16 | Zachodniopomorskie | West Pomeranian |

The maps are saved in [Mapsforge format](https://github.com/mapsforge/mapsforge), which is a great advantage.

Map files downloaded from IGPSPORT can be previewed using the Cruiser tool: [Cruiser Releases](https://github.com/devemux86/cruiser/releases)

PBF map files can be downloaded from [Geofabrik](https://download.geofabrik.de/).

## Creating Your Own Maps for IGPSPORT ##

1. **Download a PBF file** from Geofabrik as described above.

2. **Download the latest version of OSMOSIS** (currently 0.49.2): [OSMOSIS Releases](https://github.com/openstreetmap/osmosis/releases/tag/0.49.2)

3. **Download MapsForge map writer** (pre-built binary available): [mapsforge-map-writer-0.26.1-jar-with-dependencies.jar](https://github.com/mapsforge/mapsforge/releases/download/0.26.1/mapsforge-map-writer-0.26.1-jar-with-dependencies.jar)

4. **Extract osmosis-0.49.2** (requires Java 21 from [Oracle](https://www.oracle.com/java/technologies/downloads/))

5. Navigate to the `bin` directory.

6. Create a `Plugins` folder and place [mapsforge-map-writer-0.26.1-jar-with-dependencies.jar](https://github.com/mapsforge/mapsforge/releases/download/0.26.1/mapsforge-map-writer-0.26.1-jar-with-dependencies.jar) inside it.

7. In the `bin` folder, create a file named `map.bat` with the following content:

```batch
Osmosis --rbf file="./input.osm.pbf" --tag-filter reject-ways amenity=* highway=* building=* natural=* landuse=* leisure=* shop=* waterway=* man_made=* railway=* tourism=* barrier=* boundary=* power=* historic=* emergency=* office=* craft=* healthcare=* aeroway=* route=* public_transport=* bridge=* tunnel=* addr:housenumber=* addr:street=* addr:city=* addr:postcode=* name=* ref=* surface=* access=* foot=* bicycle=* motor_vehicle=* oneway=* lit=* width=* maxspeed=* mountain_pass=* religion=* tracktype=* area=* sport=* piste=* admin_level=* aerialway=* lock=* roof=* military=* wood=* --tag-filter accept-relations natural=water place=islet --used-node --rbf file="./input.osm.pbf" --tag-filter accept-ways highway=* waterway=* landuse=* natural=* place=* --tag-filter accept-relations highway=* waterway=* landuse=* natural=* place=* --used-node --merge --mapfile-writer file="./out.map" type=hd zoom-interval-conf=13,13,13,14,14,14 threads=4 tag-conf-file="./tag-igpsport.xml"
```

8. Create a file named `tag-igpsport.xml` in the same folder with the following content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tag-mapping xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" default-zoom-appear="16"
             profile-name="default-profile" xmlns="http://mapsforge.org/tag-mapping"
             xsi:schemaLocation="http://mapsforge.org/tag-mapping https://raw.githubusercontent.com/mapsforge/mapsforge/master/resources/tag-mapping.xsd">
  <ways>
    <osm-tag key="highway" value="motorway" zoom-appear="13"/>
    <osm-tag key="highway" value="motorway_link" zoom-appear="13"/>
    <osm-tag key="highway" value="primary" zoom-appear="13"/>
    <osm-tag key="highway" value="primary_link" zoom-appear="13"/>
    <osm-tag key="highway" value="secondary" zoom-appear="13"/>
    <osm-tag key="highway" value="secondary_link" zoom-appear="13"/>
    <osm-tag key="highway" value="tertiary" zoom-appear="13"/>
    <osm-tag key="highway" value="tertiary_link" zoom-appear="13"/>
    <osm-tag key="highway" value="trunk" zoom-appear="13"/>
    <osm-tag key="highway" value="trunk_link" zoom-appear="13"/>

    <osm-tag key="highway" value="bridleway" zoom-appear="14"/>
    <osm-tag key="highway" value="bus_guideway" zoom-appear="14"/>
    <osm-tag key="highway" value="construction" zoom-appear="14"/>
    <osm-tag key="highway" value="cycleway" zoom-appear="14"/>
    <osm-tag key="highway" value="footway" zoom-appear="14"/>
    <osm-tag key="highway" value="living_street" zoom-appear="14"/>
    <osm-tag key="highway" value="path" zoom-appear="14"/>
    <osm-tag key="highway" value="pedestrian" zoom-appear="14"/>
    <osm-tag key="highway" value="raceway" zoom-appear="14"/>
    <osm-tag key="highway" value="residential" zoom-appear="14"/>
    <osm-tag key="highway" value="road" zoom-appear="14"/>
    <osm-tag key="highway" value="service" zoom-appear="14"/>
    <osm-tag key="highway" value="services" zoom-appear="14"/>
    <osm-tag key="highway" value="steps" zoom-appear="14"/>
    <osm-tag key="highway" value="track" zoom-appear="14"/>
    <osm-tag key="highway" value="unclassified" zoom-appear="14"/>
  </ways>

  <ways>
    <osm-tag key="waterway" value="river" zoom-appear="13"/>
    <osm-tag key="waterway" value="riverbank" zoom-appear="13"/>
    <osm-tag key="waterway" value="canal" zoom-appear="14"/>
    <osm-tag key="waterway" value="stream" zoom-appear="14"/>
  </ways>

  <ways>
    <osm-tag key="landuse" value="forest" zoom-appear="14"/>
  </ways>

  <ways>
    <osm-tag key="natural" value="water" zoom-appear="13"/>
    <osm-tag key="place" value="islet" zoom-appear="13"/>
  </ways>
</tag-mapping>
```

9. Place your PBF map file into the `bin` folder and rename it to `input.osm.pbf`.

10. Run the `map.bat` file.

11. After several minutes or hours, you'll have your output file `output.map`, which you can upload to your cycling computer.

### IGPSport Map File Naming Convention ###

Map files must have a specific name format. Unfortunately, IGPSport support doesn't share how this naming convention works. The filename contains: country code, 4 digits representing a sequential number, and date in YYMMDD format (these values can be modified freely). The remaining characters is deciphered, below in section [IGPSport File Name Structure](#igpsport-file-name-structure-decoded).

#### Polish Voivodeship File Names ####

| Region | File Name Pattern |
|--------|-------------------|
| 🇵🇱 Lower Silesia | PL01002505163F423C01X01R |
| 🇵🇱 Kuyavian-Pomeranian | PL02002505163GN21A01N01K |
| 🇵🇱 Łódź | PL03002505163H622Q01O01M |
| 🇵🇱 Lublin | PL04002505163JF22U01M024 |
| 🇵🇱 Lubusz | PL05002505163EY21Z01701U |
| 🇵🇱 Lesser Poland | PL06002505163HT24N01I01C |
| 🇵🇱 Masovia | PL07002505163HX21L02H02L |
| 🇵🇱 Opole | PL08002505163GG23Y015019 |
| 🇵🇱 Subcarpathian | PL09002505163J424C01K01U |
| 🇵🇱 Podlachia | PL10002505163JE20L01J02B |
| 🇵🇱 Pomeranian | PL11002505163GB20401W01I |
| 🇵🇱 Silesia | PL12002505163H524201901Q |
| 🇵🇱 Holy Cross | PL13002505163I723T01F017 |
| 🇵🇱 Warmia-Masuria | PL14002505163HU20J02D01H |
| 🇵🇱 Greater Poland | PL15002505163FQ21E02502P |
| 🇵🇱 West Pomeranian | PL16002505163EO20F01U024 |
| 🇵🇱 Poland (full) | PL00002505163E920007106D |
| 🇨🇿 Czech Republic | CZ00002505163DD24304G02J |

#### Country File Names ####

| Country | File Name |
|---------|-----------|
| 🇦🇷 Argentina | AR00002503171UZ3JT0DA0RX.map |
| 🇦🇺 Australia | AU00002503174FN3BK1OB105.map |
| 🇦🇹 Austria | AT00001111113BR26204W02L.map |
| 🇧🇾 Belarus | BY00002408073JS1YF06R066.map |
| 🇧🇪 Belgium | BE000025031737923N02L022.map |
| 🇧🇴 Bolivia | BO00002503281XI3BT07Z09C.map |
| 🇧🇦 Bosnia and Herzegovina | BA00002503313FO29J02J02H.map |
| 🇧🇷 Brazil | BR00002503251UO32A0SV0QB.map |
| 🇧🇬 Bulgaria | BG00002407153JK29V04B03C.map |
| 🇰🇭 Cambodia | KH00002503174YE2VY03N03J.map |
| 🇨🇦 Canada | CA00002311170EV08Z25I48I.map |
| 🇨🇱 Chile | CL000025031718L3GW0R80WF.map |
| 🇨🇳 China | CN00002503204G32141330UK.map |
| 🇨🇴 Colombia | CO00002503171Q12VL0AS0CY.map |
| 🇭🇷 Croatia | HR00002503173EA28C03T03T.map |
| 🇨🇾 Cyprus | CY00002408273Q62HK01H00W.map |
| 🇨🇿 Czech Republic | CZ00002503173DD24304C02J.map |
| 🇩🇰 Denmark | DK00002503173AV1WT04I03N.map |
| 🇪🇨 Ecuador | EC00002503171JM34P0AR04A.map |
| 🇪🇬 Egypt | EG00002501033KQ2KL08L08G.map |
| 🇫🇮 Finland | FI00002311173GN1E00940GP.map |
| 🇫🇷 France | FR000025031732I23V09I096.map |
| 🇬🇪 Georgia | GE00002503173V02AR04C02K.map |
| 🇩🇪 Germany | DE000025031739H1ZQ05Z083.map |
| 🇬🇷 Greece | GR00002502103I02CN06I05N.map |
| 🇸🇦 Gulf Cooperation Council | GC00002503173RK2JU0G10BL.map |
| 🇬🇾 Guyana | GY000025031722W30B03I04W.map |
| 🇭🇺 Hungary | HU00002503173FY26904I032.map |
| 🇮🇳 India | IN00002503174CK2HO0JC0JT.map |
| 🇮🇩 Indonesia | ID00002503174TT31X0TL0AV.map |
| 🇮🇪 Ireland | IE00002406252Z01YM032056.map |
| 🇮🇱 Israel | IL00001111113QT2J901T03O.map |
| 🇮🇹 Italy | IT000025031739Y27S07J09Z.map |
| 🇯🇵 Japan | JP00002503175BH29D0JN0J8.map |
| 🇰🇿 Kazakhstan | KZ00002503313Z11ZA0Q10HM.map |
| 🇰🇬 Kyrgyzstan | KG00002503314DJ2BA06X03J.map |
| 🇱🇹 Lithuania | LT00002502103IZ1Y503U034.map |
| 🇱🇺 Luxembourg | LU000025031739D24Y00K00S.map |
| 🇲🇾 Malaysia | MY00002503254WR3100CF04I.map |
| 🇲🇽 Mexico | MX000025032512Y2JU0K00CN.map |
| 🇲🇪 Montenegro | ME00002503313HE2B201A01J.map |
| 🇲🇦 Morocco | MA00002311262UY2GM0A70BQ.map |
| 🇲🇲 Myanmar | MM00002503174S02MX05R0CR.map |
| 🇳🇱 Netherlands | AN000025031737T21J02J02Z.map |
| 🇳🇿 New Zealand | NZ000025021000Q3P46A80JX.map |
| 🇲🇰 North Macedonia | MK00002504103IO2C201V01G.map |
| 🇳🇴 Norway | NO00002311172X90ME0U61B6.map |
| 🇵🇾 Paraguay | PY000025031721Y3I005L068.map |
| 🇵🇪 Peru | PE00002503171QD35G08B0C6.map |
| 🇵🇭 Philippines | PH000025031754P2S40930AR.map |
| 🇵🇱 Poland | PL00002503173EJ20506N068.map |
| 🇵🇹 Portugal | PT00002503172M02C80FW07V.map |
| 🇷🇴 Romania | RO00002503173IH26U06404A.map |
| 🇷🇸 Serbia | RS00002412313HI28M03C03P.map |
| 🇸🇰 Slovakia | SK00002311173G525G042020.map |
| 🇸🇮 Slovenia | SI00002311173E528002701Q.map |
| 🇰🇷 South Korea | KR00002503205CJ2F804L04C.map |
| 🇪🇸 Spain | ES00002503182ZW2AX08M070.map |
| 🇸🇷 Suriname | SR000025031724L31Y03B039.map |
| 🇸🇪 Sweden | SE00002503173CP1GK08H0J1.map |
| 🇨🇭 Switzerland | CH000025031739G27903001Y.map |
| 🇹🇯 Tajikistan | TJ00002503314CB2D904X03L.map |
| 🇹🇭 Thailand | TH00002503174V42SE05J09V.map |
| 🇹🇷 Turkey | TR00002503173M02C50CG060.map |
| 🇹🇲 Turkmenistan | TM00002503313ZB2810CY0A7.map |
| 🇺🇦 Ukraine | UA00002503173JK22Q0BN07O.map |
| 🇬🇧 United Kingdom | UK00002503172X41SZ09S0CC.map |
| 🇺🇾 Uruguay | UY000025031724D3NK04606W.map |
| 🇺🇿 Uzbekistan | UZ000025033144F28M0BM086.map |
| 🇻🇪 Venezuela | VE00002503171VE2VQ08M0AE.map |
| 🇻🇳 Vietnam | VN00002503174Y22QK0800A7.map |

### IGPSPORT File Name Structure (Decoded) ###

After reverse-engineering the IGPSPORT map file naming convention, we discovered how the geographic information is encoded. The file name follows this structure:

```
[CC][RRRR][DATE][GEO_CODE]
```

Where:
- **CC** - Country code (2 characters, e.g., `PL` for Poland, `CZ` for Czech Republic)
- **RRRR** - Region code (4 digits, e.g., `0000` for whole country, `0001`-`0016` for Polish voivodeships, it doesn't matter)
- **DATE** - Date in format `YYMMDD` (6 digits, can be modified freely)
- **GEO_CODE** - Geographic bounding box encoded in Base36 (12 characters)

#### Geographic Code Encoding (Tile-based at Zoom 13) ####

The 12-character geographic code encodes the map's bounding box using a tile-based system at zoom level 13 (N = 8192 tiles per side), with values encoded in Base36:

| Position | Length | Name | Description | Formula |
|----------|--------|------|-------------|---------|
| 1-3 | 3 chars | **MIN_LON** | Western border (min longitude) | `Base36(floor((MIN_LON + 180) / 360 × N))` |
| 4-6 | 3 chars | **MAX_LAT** | Northern border (max latitude) | `Base36(floor(mercatorY(MAX_LAT) × N))` |
| 7-9 | 3 chars | **LON_SPAN-1** | Width in tiles minus 1 | `Base36(LON_END - LON_START)` where `LON_END = floor((MAX_LON + 180) / 360 × N)` |
| 10-12 | 3 chars | **LAT_SPAN-1** | Height in tiles minus 1 | `Base36(LAT_END - LAT_START)` where `LAT_END = floor(mercatorY(MIN_LAT) × N)` |

**Web Mercator projection for latitude:**
```
mercatorY = (1 - ln(tan(lat) + 1/cos(lat)) / π) / 2
```
> **Note:** `lat` must be in **radians** (lat_rad = lat_deg × π / 180)

#### Base36 Encoding ####

Base36 uses characters `0-9` and `A-Z` (case-insensitive):
- `0` = 0, `9` = 9, `A` = 10, `Z` = 35
- Each 3-character group can represent values from 0 to 46,655 (36³ - 1)

#### Step-by-Step Calculation Example ####

Let's calculate the GEO_CODE for Poland with bounding box:
- **Min Longitude (West):** 14.1°
- **Max Longitude (East):** 24.15°
- **Min Latitude (South):** 49.0°
- **Max Latitude (North):** 54.85°

**Constants:**
- Zoom level: 13
- N = 2^13 = 8192 tiles per side

---

**Step 1: Calculate MIN_LON (Position 1-3)**

```
LON_START = floor((MIN_LON + 180) / 360 × N)
LON_START = floor((14.1 + 180) / 360 × 8192)
LON_START = floor(194.1 / 360 × 8192)
LON_START = floor(0.53917 × 8192)
LON_START = floor(4416.83)
LON_START = 4416
```

Convert 4416 to Base36:
```
4416 ÷ 36 = 122 remainder 24  → 24 = 'O'
122 ÷ 36 = 3 remainder 14    → 14 = 'E'
3 ÷ 36 = 0 remainder 3       → 3 = '3'

Result: 3EO
```

---

**Step 2: Calculate MAX_LAT (Position 4-6)**

First convert MAX_LAT to radians:
```
MAX_LAT_rad = 54.85 × π / 180 = 0.9573 radians
```

Calculate Mercator Y:
```
mercatorY = (1 - ln(tan(MAX_LAT_rad) + 1/cos(MAX_LAT_rad)) / π) / 2
mercatorY = (1 - ln(tan(0.9573) + 1/cos(0.9573)) / π) / 2
mercatorY = (1 - ln(1.4191 + 1.7321) / π) / 2
mercatorY = (1 - ln(3.1512) / π) / 2
mercatorY = (1 - 1.1477 / 3.1416) / 2
mercatorY = (1 - 0.3652) / 2
mercatorY = 0.3174
```

Calculate LAT_START:
```
LAT_START = floor(mercatorY × N)
LAT_START = floor(0.3174 × 8192)
LAT_START = floor(2600.14)
LAT_START = 2600
```

Convert 2600 to Base36:
```
2600 ÷ 36 = 72 remainder 8   → 8 = '8'
72 ÷ 36 = 2 remainder 0      → 0 = '0'
2 ÷ 36 = 0 remainder 2       → 2 = '2'

Result: 208
```

---

**Step 3: Calculate LON_SPAN-1 (Position 7-9)**

```
LON_END = floor((MAX_LON + 180) / 360 × N)
LON_END = floor((24.15 + 180) / 360 × 8192)
LON_END = floor(204.15 / 360 × 8192)
LON_END = floor(4645.87)
LON_END = 4645

LON_SPAN-1 = LON_END - LON_START = 4645 - 4416 = 229
```

Convert 229 to Base36:
```
229 ÷ 36 = 6 remainder 13    → 13 = 'D'
6 ÷ 36 = 0 remainder 6       → 6 = '6'

Result: 06D (padded to 3 chars)
```

---

**Step 4: Calculate LAT_SPAN-1 (Position 10-12)**

Calculate LAT_END for MIN_LAT (49.0°):
```
MIN_LAT_rad = 49.0 × π / 180 = 0.8552 radians
mercatorY = (1 - ln(tan(0.8552) + 1/cos(0.8552)) / π) / 2
mercatorY = (1 - ln(1.1504 + 1.5243) / π) / 2
mercatorY = (1 - ln(2.6747) / π) / 2
mercatorY = (1 - 0.9838 / 3.1416) / 2
mercatorY = (1 - 0.3131) / 2
mercatorY = 0.3434

LAT_END = floor(0.3434 × 8192) = 2813
```

Calculate span:
```
LAT_SPAN-1 = LAT_END - LAT_START = 2813 - 2600 = 213
```

Convert 213 to Base36:
```
213 ÷ 36 = 5 remainder 33    → 33 = 'X'
5 ÷ 36 = 0 remainder 5       → 5 = '5'

Result: 05X (padded to 3 chars)
```

---

**Final Result:**

```
GEO_CODE = 3EO + 208 + 06D + 05X = 3EO20806D05X
Full filename: PL0000250317 3EO20806D05X .map
```

This tile-based encoding allows the cycling computer to verify that the map file covers the expected geographic area.

### Additional Notes for IGPSPORT ###

- All available tags can be found on the Mapsforge repository: [tag-mapping.xml](https://github.com/mapsforge/mapsforge/blob/master/mapsforge-map-writer/src/main/config/tag-mapping.xml)
- To disable a map file on the computer, simply change its extension (e.g., to `.old`). This way, when testing maps, you don't need to delete the originals.

## Information about maps for XOSS NAV Plus ##

By default, the XOSS NAV Plus cycling computer comes with maps of Asia preloaded. The map files can be downloaded from the official XOSS website: [XOSS Download Maps](https://www.xoss.co/#/download/maps). However, as is often the case with Chinese products, the maps were generated on August 12, 2023. Upon inspection, it's unclear if the file used for generation is even older. Hence, there's an idea to try generating maps ourselves.

The maps are saved in the [Mapsforge format](https://github.com/mapsforge/mapsforge), which is advantageous because if XOSS ceases to provide updates (if any), users can generate their own.

Map files downloaded from XOSS can be previewed using the Cruiser tool available at [Cruiser](https://github.com/devemux86/cruiser/releases).

On the [BBBike Extract](https://extract.bbbike.org/) website, users can generate their own map extracts in Protocolbuffer (PBF) format. PBF map files can also be downloaded from [Geofabrik](https://download.geofabrik.de/).

### Now, let's get a bit technical on how to create our own maps. ###

We'll need a PBF file, which I've described how to obtain above.

1. Download the latest version of OSMOSIS. Currently, it's version 0.49.2 available at [OSMOSIS Releases](https://github.com/openstreetmap/osmosis/releases/tag/0.49.2).

2. Download MapsForge. You can compile it from the source, but pre-built binaries are available as well: [mapsforge-map-writer-0.26.1-jar-with-dependencies.jar](https://github.com/mapsforge/mapsforge/releases/download/0.26.1/mapsforge-map-writer-0.26.1-jar-with-dependencies.jar).

3. Unzip osmosis-0.49.2 (requires Java 21, available at [Oracle](https://www.oracle.com/pl/java/technologies/downloads/)).

4. Navigate to the 'bin' directory.

5. Create a folder named 'Plugins' and place [mapsforge-map-writer-0.26.1-jar-with-dependencies.jar](https://github.com/mapsforge/mapsforge/releases/download/0.26.1/mapsforge-map-writer-0.26.1-jar-with-dependencies.jar) inside it.

6. In the 'bin' folder, create a file named `map.bat` and add the following content:

``` osmosis --rbf file="input.pbf" --tf accept-ways highway=* --tf reject-relations --used-node --mapfile-writer file="output.map" type=hd zoom-interval-conf=14,0,16 ```

7. Place your PBF map file into the 'bin' folder and rename it to input.pbf.

8. Run the map.bat file you created.

9. After several minutes or hours (generating a map of Poland takes around an 2 hours on Ryzen 7 4800), you'll have your output map file (output.map), which you can then upload to your cycling computer.


### Additional info ###
1. The bike computer moderately supports maps that are not squares. In other words, it does support them, but if we generate a map of the Czech Republic and a map of Poland and simultaneously upload them to the bike computer's folder, the southern part of Poland will not be accessible.
2. If we want to disable a file on the bike computer, simply change its extension to .old. This way, while testing maps, we don't have to delete the originals; changing the extension is sufficient.
3. Maps do not load at the default zoom level; however, if we generate a zoom level not supported by the bike computer, upon switching to navigation, the bike computer hangs for 5 minutes before resetting. Fortunately, I couldn't crash the bike computer with a bad map file.

---