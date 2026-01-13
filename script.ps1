$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrEmpty($SCRIPT_DIR)) {
    $SCRIPT_DIR = Get-Location
}

# Download and extract osmosis if not present
$OSMOSIS_VERSION = "0.49.2"
$OSMOSIS_DIR = Join-Path $SCRIPT_DIR "osmosis-$OSMOSIS_VERSION"

if (-not (Test-Path $OSMOSIS_DIR)) {
    Write-Host "Osmosis not found. Downloading osmosis-$OSMOSIS_VERSION..."
    $OSMOSIS_URL = "https://github.com/openstreetmap/osmosis/releases/download/$OSMOSIS_VERSION/osmosis-$OSMOSIS_VERSION.zip"
    $OSMOSIS_ZIP = Join-Path $SCRIPT_DIR "osmosis-$OSMOSIS_VERSION.zip"
    
    Invoke-WebRequest -Uri $OSMOSIS_URL -OutFile $OSMOSIS_ZIP -UseBasicParsing
    
    Write-Host "Extracting osmosis..."
    Expand-Archive -Path $OSMOSIS_ZIP -DestinationPath $SCRIPT_DIR -Force
    
    Write-Host "Cleaning up..."
    Remove-Item $OSMOSIS_ZIP
    
    Write-Host "Osmosis $OSMOSIS_VERSION installed successfully."
    Write-Host ""
}

# Download and install Mapsforge writer plugin if not present
$MAPSFORGE_WRITER_VERSION = "0.25.0"
$MAPSFORGE_WRITER_JAR = Join-Path $OSMOSIS_DIR "lib\mapsforge-map-writer-$MAPSFORGE_WRITER_VERSION-jar-with-dependencies.jar"

if (-not (Test-Path $MAPSFORGE_WRITER_JAR)) {
    Write-Host "Mapsforge writer plugin not found. Downloading version $MAPSFORGE_WRITER_VERSION..."
    $MAPSFORGE_URL = "https://repo1.maven.org/maven2/org/mapsforge/mapsforge-map-writer/$MAPSFORGE_WRITER_VERSION/mapsforge-map-writer-$MAPSFORGE_WRITER_VERSION-jar-with-dependencies.jar"
    
    Invoke-WebRequest -Uri $MAPSFORGE_URL -OutFile $MAPSFORGE_WRITER_JAR -UseBasicParsing
    
    Write-Host "Mapsforge writer plugin installed successfully."
    Write-Host ""
}

# Create wrapper batch script that includes Mapsforge in classpath
$OSMOSIS_WRAPPER = Join-Path $OSMOSIS_DIR "bin\osmosis-with-mapsforge.bat"
if (-not (Test-Path $OSMOSIS_WRAPPER)) {
    $MAPSFORGE_JAR_NAME = "mapsforge-map-writer-$MAPSFORGE_WRITER_VERSION-jar-with-dependencies.jar"
    $OSMOSIS_BAT = Join-Path $OSMOSIS_DIR "bin\osmosis.bat"
    
    # Read original osmosis.bat
    $content = Get-Content $OSMOSIS_BAT -Raw
    
    # Add Mapsforge JAR to classpath
    $content = $content -replace '(set CLASSPATH=)', "`$1%APP_HOME%\lib\$MAPSFORGE_JAR_NAME;"
    
    # Write wrapper
    Set-Content -Path $OSMOSIS_WRAPPER -Value $content
}

$TAG_CONF_FILE = Join-Path $SCRIPT_DIR "tag-igpsport.xml"
$THREADS = 1
$TMP_DIR = Join-Path $SCRIPT_DIR "tmp"
$env:JAVA_OPTS = "-Xms1g -Xmx8g -Djava.io.tmpdir=$TMP_DIR"

# Create directories
$DOWNLOAD_DIR = Join-Path $SCRIPT_DIR "download"
$OUTPUT_DIR = Join-Path $SCRIPT_DIR "output"

if (-not (Test-Path $TMP_DIR)) { New-Item -ItemType Directory -Path $TMP_DIR | Out-Null }
if (-not (Test-Path $DOWNLOAD_DIR)) { New-Item -ItemType Directory -Path $DOWNLOAD_DIR | Out-Null }
if (-not (Test-Path $OUTPUT_DIR)) { New-Item -ItemType Directory -Path $OUTPUT_DIR | Out-Null }

# Check if maps.csv exists
$CSV_FILE = Join-Path $SCRIPT_DIR "maps.csv"
if (-not (Test-Path $CSV_FILE)) {
    Write-Error "ERROR: maps.csv not found in directory: $SCRIPT_DIR"
    exit 1
}

# Read CSV file and download files
Write-Host "Reading maps.csv..."
$PBF_FILES = @()
$POLY_FILES = @()
$ORIGINAL_NAMES = @()

$csv_data = Import-Csv -Path $CSV_FILE

foreach ($row in $csv_data) {
    $original_name = $row.original_name
    $pbf_url = $row.pbf_url
    $poly_url = $row.poly_url
    
    if ([string]::IsNullOrWhiteSpace($original_name)) {
        continue
    }
    
    Write-Host ""
    Write-Host "Processing entry: $original_name"
    
    # Download PBF file
    $pbf_filename = Split-Path $pbf_url -Leaf
    $pbf_path = Join-Path $DOWNLOAD_DIR $pbf_filename
    
    if (-not (Test-Path $pbf_path)) {
        Write-Host "  Downloading PBF: $pbf_filename..."
        Invoke-WebRequest -Uri $pbf_url -OutFile $pbf_path -UseBasicParsing
        Write-Host "  PBF downloaded."
    } else {
        Write-Host "  PBF already exists: $pbf_filename"
    }
    
    # Download Poly file
    $poly_filename = Split-Path $poly_url -Leaf
    $poly_path = Join-Path $DOWNLOAD_DIR $poly_filename
    
    if (-not (Test-Path $poly_path)) {
        Write-Host "  Downloading Poly: $poly_filename..."
        Invoke-WebRequest -Uri $poly_url -OutFile $poly_path -UseBasicParsing
        Write-Host "  Poly downloaded."
    } else {
        Write-Host "  Poly already exists: $poly_filename"
    }
    
    $PBF_FILES += $pbf_path
    $POLY_FILES += $poly_path
    $ORIGINAL_NAMES += $original_name
}

if ($PBF_FILES.Count -eq 0) {
    Write-Error "ERROR: No entries found in maps.csv"
    exit 1
}

Write-Host ""
Write-Host "=========================================="
Write-Host "Found $($PBF_FILES.Count) entries to process"
Write-Host "=========================================="
Write-Host ""

$PRODUCT_CODE = "2300"

if (-not (Test-Path $TAG_CONF_FILE)) {
    Write-Error "ERROR: Tag configuration file not found: $TAG_CONF_FILE"
    exit 1
}
$MagicString = "mapsforge binary OSM"
$DefaultZoom = 13
$Zoom = 1 -shl $DefaultZoom
$Base36Chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

function Read-BigEndianLong {
    param([System.IO.BinaryReader]$reader)
    $bytes = $reader.ReadBytes(8)
    [long]$result = 0
    foreach ($b in $bytes) {
        $result = ($result -shl 8) + $b
    }
    return $result
}

function Read-BigEndianInt32 {
    param([System.IO.BinaryReader]$reader)
    $b = $reader.ReadBytes(4)
    if ($b.Length -ne 4) { throw "EOF while reading int32" }
    $u = ([uint32]$b[0] -shl 24) -bor
         ([uint32]$b[1] -shl 16) -bor
         ([uint32]$b[2] -shl  8) -bor
         ([uint32]$b[3])
    if ($u -ge 0x80000000) { return [int32]($u - 0x100000000) }
    return [int32]$u
}

function ConvertTo-Base36 {
    param([int]$value, [int]$length)
    if ($value -lt 0) { $value = 0 }
    $result = New-Object char[] $length
    for ($i = $length - 1; $i -ge 0; $i--) {
        $result[$i] = $Base36Chars[$value % 36]
        $value = [math]::Floor($value / 36)
    }
    return -join $result
}

function ConvertTo-TileX {
    param([double]$lon, [int]$tilesPerSide)
    [math]::Floor((($lon + 180.0) / 360.0) * $tilesPerSide)
}

function ConvertTo-TileY {
    param([double]$lat, [int]$tilesPerSide)
    $rad = $lat * [math]::PI / 180.0
    [math]::Floor(((1.0 - [math]::Log([math]::Tan($rad) + 1.0 / [math]::Cos($rad)) / [math]::PI) / 2.0) * $tilesPerSide)
}

function Get-GeoName {
    param([double]$minLng, [double]$maxLng, [double]$minLat, [double]$maxLat)
    $xStart = ConvertTo-TileX -lon $minLng -tilesPerSide $Zoom
    $yStart = ConvertTo-TileY -lat $maxLat -tilesPerSide $Zoom
    $xEnd = ConvertTo-TileX -lon $maxLng -tilesPerSide $Zoom
    $yEnd = ConvertTo-TileY -lat $minLat -tilesPerSide $Zoom
    $xSpan = $xEnd - $xStart + 1
    $ySpan = $yEnd - $yStart + 1
    (ConvertTo-Base36 -value $xStart -length 3) +
    (ConvertTo-Base36 -value $yStart -length 3) +
    (ConvertTo-Base36 -value ($xSpan - 1) -length 3) +
    (ConvertTo-Base36 -value ($ySpan - 1) -length 3)
}

$fileIndex = 0
for ($i = 0; $i -lt $PBF_FILES.Count; $i++) {
    $fileIndex++
    $INPUT_FILE = $PBF_FILES[$i]
    $POLY_FILE = $POLY_FILES[$i]
    $ORIGINAL_NAME = $ORIGINAL_NAMES[$i]
    $fileName = Split-Path -Leaf $INPUT_FILE
    
    # Extract country code from original filename (first 2 characters)
    $COUNTRY_CODE = $ORIGINAL_NAME.Substring(0, 2)
    
    Write-Host "=========================================="
    Write-Host "Processing [$fileIndex/$($PBF_FILES.Count)]"
    Write-Host "  PBF File:      $fileName"
    Write-Host "  Poly File:     $(Split-Path -Leaf $POLY_FILE)"
    Write-Host "  Original Name: $ORIGINAL_NAME"
    Write-Host "  Country Code:  $COUNTRY_CODE"
    Write-Host "=========================================="
    
    $OUTPUT_FILE = Join-Path $OUTPUT_DIR "out_$fileIndex.map"
    
    Write-Host "Running osmosis..."
    $OSMOSIS_CMD = Join-Path $OSMOSIS_DIR "bin\osmosis-with-mapsforge.bat"
    & cmd /c "`"$OSMOSIS_CMD`" --rbf file=`"$INPUT_FILE`" --bounding-polygon file=`"$POLY_FILE`" --tag-filter reject-ways amenity=* highway=* building=* natural=* landuse=* leisure=* shop=* waterway=* man_made=* railway=* tourism=* barrier=* boundary=* power=* historic=* emergency=* office=* craft=* healthcare=* aeroway=* route=* public_transport=* bridge=* tunnel=* addr:housenumber=* addr:street=* addr:city=* addr:postcode=* name=* ref=* surface=* access=* foot=* bicycle=* motor_vehicle=* oneway=* lit=* width=* maxspeed=* mountain_pass=* religion=* tracktype=* area=* sport=* piste=* admin_level=* aerialway=* lock=* roof=* military=* wood=* --tag-filter accept-relations natural=water place=islet --used-node --rbf file=`"$INPUT_FILE`" --bounding-polygon file=`"$POLY_FILE`" --tag-filter accept-ways highway=* waterway=* landuse=* natural=* place=* --tag-filter accept-relations highway=* waterway=* landuse=* natural=* place=* --used-node --merge --mapfile-writer file=`"$OUTPUT_FILE`" type=hd zoom-interval-conf=13,13,13,14,14,14 threads=$THREADS tag-conf-file=`"$TAG_CONF_FILE`""
    
    if (-not (Test-Path $OUTPUT_FILE)) {
        Write-Warning "WARNING: Osmosis did not generate file for: $fileName - skipping"
        continue
    }
    
    Write-Host "Osmosis completed. Generating name..."
    
    $fs = [System.IO.File]::OpenRead($OUTPUT_FILE)
    $reader = New-Object System.IO.BinaryReader($fs)
    try {
        # Read magic string
        $magicBytes = $reader.ReadBytes($MagicString.Length)
        $magic = [System.Text.Encoding]::ASCII.GetString($magicBytes)
        
        if ($magic -ne $MagicString) {
            Write-Warning "WARNING: Invalid .map file for: $fileName - skipping"
            continue
        }
        
        # Skip 16 bytes (4 + 4 + 8)
        $null = $reader.ReadBytes(16)
        
        # Read date timestamp (8 bytes, big-endian long)
        $dateTimestamp = Read-BigEndianLong -reader $reader
        $dateOfCreation = [DateTimeOffset]::FromUnixTimeMilliseconds($dateTimestamp).DateTime
        $dateString = $dateOfCreation.ToString("yyMMdd")
        
        # Read bounding box (4 int32s)
        $minLatMicro = Read-BigEndianInt32 $reader
        $minLngMicro = Read-BigEndianInt32 $reader
        $maxLatMicro = Read-BigEndianInt32 $reader
        $maxLngMicro = Read-BigEndianInt32 $reader
        
        $minLat = $minLatMicro / 1000000.0
        $minLng = $minLngMicro / 1000000.0
        $maxLat = $maxLatMicro / 1000000.0
        $maxLng = $maxLngMicro / 1000000.0
        
        $geoName = Get-GeoName -minLng $minLng -maxLng $maxLng -minLat $minLat -maxLat $maxLat
        
        $newName = "${COUNTRY_CODE}${PRODUCT_CODE}${dateString}${geoName}"
        $newPath = Join-Path $OUTPUT_DIR "$newName.map"
        
        Write-Host "Map Details:"
        Write-Host "  Date:        $dateString"
        Write-Host "  Bounding Box: minLat=$minLat minLng=$minLng maxLat=$maxLat maxLng=$maxLng"
        Write-Host "  Geo Code:    $geoName"
        Write-Host "  Generated:   $newName.map"
        
        if (Test-Path $newPath) {
            Remove-Item $newPath -Force
        }
        
        Move-Item -Path $OUTPUT_FILE -Destination $newPath
        
        Write-Host ""
    }
    finally {
        $reader.Close()
        $fs.Close()
    }
}

Write-Host "=========================================="
Write-Host "Done! Processed $($PBF_FILES.Count) files."
Write-Host "=========================================="