$ErrorActionPreference = "Stop"

$SCRIPT_DIR = $PSScriptRoot

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

# Create wrapper script that includes Mapsforge in classpath
$OSMOSIS_WRAPPER = Join-Path $OSMOSIS_DIR "bin\osmosis-with-mapsforge.bat"
$OSMOSIS_SCRIPT = Join-Path $OSMOSIS_DIR "bin\osmosis.bat"

if (-not (Test-Path $OSMOSIS_WRAPPER)) {
    $MAPSFORGE_JAR_NAME = "mapsforge-map-writer-$MAPSFORGE_WRITER_VERSION-jar-with-dependencies.jar"
    $content = Get-Content $OSMOSIS_SCRIPT -Raw
    $content = $content -replace '(set CLASSPATH=%APP_HOME%)', "`$1\lib\$MAPSFORGE_JAR_NAME;%APP_HOME%"
    $content | Set-Content $OSMOSIS_WRAPPER
}

$TAG_CONF_FILE = Join-Path $SCRIPT_DIR "tag-igpsport.xml"
$TAG_TRANSFORM_FILE = Join-Path $SCRIPT_DIR "tag-igpsport-transform.xml"
$THREADS = 4
$TMP_DIR = Join-Path $SCRIPT_DIR "tmp"
$env:JAVA_OPTS = "-Xms1g -Xmx8g -Djava.io.tmpdir=$TMP_DIR"
$env:CLASSPATH = "$MAPSFORGE_WRITER_JAR;$env:CLASSPATH"

# Create directories
$DOWNLOAD_DIR = Join-Path $SCRIPT_DIR "download"
$OUTPUT_DIR = Join-Path $SCRIPT_DIR "output"

New-Item -ItemType Directory -Force -Path $TMP_DIR | Out-Null
New-Item -ItemType Directory -Force -Path $DOWNLOAD_DIR | Out-Null
New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

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

$csv = Import-Csv $CSV_FILE

foreach ($row in $csv) {
    $original_name = $row.'Original filename'
    $pbf_url = $row.'OSM BPF URL'
    $poly_url = $row.'Poly URL'
    
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

if (-not (Test-Path $TAG_CONF_FILE)) {
    Write-Error "ERROR: Tag configuration file not found: $TAG_CONF_FILE"
    exit 1
}

if (-not (Test-Path $TAG_TRANSFORM_FILE)) {
    Write-Error "ERROR: Tag transform file not found: $TAG_TRANSFORM_FILE"
    exit 1
}

$MAGIC_STRING = "mapsforge binary OSM"
$DEFAULT_ZOOM = 13
$ZOOM = [math]::Pow(2, $DEFAULT_ZOOM)
$BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

function Read-BigEndianInt32 {
    param([System.IO.BinaryReader]$reader)
    
    $bytes = $reader.ReadBytes(4)
    if ($bytes.Length -lt 4) {
        throw "EOF while reading int32"
    }
    
    [Array]::Reverse($bytes)
    return [BitConverter]::ToInt32($bytes, 0)
}

function Read-BigEndianInt64 {
    param([System.IO.BinaryReader]$reader)
    
    $bytes = $reader.ReadBytes(8)
    if ($bytes.Length -lt 8) {
        throw "EOF while reading int64"
    }
    
    [Array]::Reverse($bytes)
    return [BitConverter]::ToInt64($bytes, 0)
}

function Convert-ToBase36 {
    param([int]$value, [int]$length)
    
    if ($value -lt 0) {
        $value = 0
    }
    
    $result = ""
    for ($i = 0; $i -lt $length; $i++) {
        $result = $BASE36_CHARS[$value % 36] + $result
        $value = [math]::Floor($value / 36)
    }
    
    return $result
}

function Convert-ToTileX {
    param([double]$lon, [double]$tiles_per_side)
    
    return [math]::Floor((($lon + 180.0) / 360.0) * $tiles_per_side)
}

function Convert-ToTileY {
    param([double]$lat, [double]$tiles_per_side)
    
    $lat_rad = $lat * [math]::PI / 180.0
    return [math]::Floor(((1.0 - ([math]::Log([math]::Tan($lat_rad) + (1.0 / [math]::Cos($lat_rad))) / [math]::PI)) / 2.0) * $tiles_per_side)
}

function Get-GeoName {
    param([double]$min_lng, [double]$max_lng, [double]$min_lat, [double]$max_lat)
    
    $x_start = Convert-ToTileX $min_lng $ZOOM
    $y_start = Convert-ToTileY $max_lat $ZOOM
    $x_end = Convert-ToTileX $max_lng $ZOOM
    $y_end = Convert-ToTileY $min_lat $ZOOM
    
    $x_span = $x_end - $x_start + 1
    $y_span = $y_end - $y_start + 1
    
    return "$(Convert-ToBase36 $x_start 3)$(Convert-ToBase36 $y_start 3)$(Convert-ToBase36 ($x_span - 1) 3)$(Convert-ToBase36 ($y_span - 1) 3)"
}

$file_index = 0
for ($i = 0; $i -lt $PBF_FILES.Count; $i++) {
    $file_index++
    $INPUT_FILE = $PBF_FILES[$i]
    $POLY_FILE = $POLY_FILES[$i]
    $ORIGINAL_NAME = $ORIGINAL_NAMES[$i]
    $file_name = Split-Path $INPUT_FILE -Leaf
    
    # Extract country code from original filename (first 2 characters)
    $COUNTRY_CODE = $ORIGINAL_NAME.Substring(0, 2)
    
    # Extract product code from original filename (characters 2-5, 0-indexed)
    $PRODUCT_CODE = $ORIGINAL_NAME.Substring(2, 4)
    
    Write-Host "=========================================="
    Write-Host "Processing [$file_index/$($PBF_FILES.Count)]"
    Write-Host "  PBF File:      $file_name"
    Write-Host "  Poly File:     $(Split-Path $POLY_FILE -Leaf)"
    Write-Host "  Original Name: $ORIGINAL_NAME"
    Write-Host "  Country Code:  $COUNTRY_CODE"
    Write-Host "  Product Code:  $PRODUCT_CODE"
    Write-Host "=========================================="
    
    $OUTPUT_FILE = Join-Path $OUTPUT_DIR "out_$file_index.map"
    
    Write-Host "Running osmosis..."
    & $OSMOSIS_WRAPPER `
        --read-pbf-fast "file=$INPUT_FILE" `
        --bounding-polygon "file=$POLY_FILE" `
        --tag-transform "file=$TAG_TRANSFORM_FILE" `
        --mapfile-writer "file=$OUTPUT_FILE" type=hd zoom-interval-conf=13,13,13,14,14,14 threads=$THREADS tag-conf-file="$TAG_CONF_FILE"
    
    if (-not (Test-Path $OUTPUT_FILE)) {
        Write-Warning "Osmosis did not generate file for: $file_name - skipping"
        continue
    }
    
    Write-Host "Osmosis completed. Generating name..."
    
    try {
        $stream = [System.IO.File]::OpenRead($OUTPUT_FILE)
        $reader = New-Object System.IO.BinaryReader($stream)
        
        # Read magic string
        $magicBytes = $reader.ReadBytes($MAGIC_STRING.Length)
        $magic = [System.Text.Encoding]::ASCII.GetString($magicBytes)
        
        if ($magic -ne $MAGIC_STRING) {
            Write-Warning "Invalid .map file for: $file_name - skipping"
            continue
        }
        
        # Skip 16 bytes (4 + 4 + 8)
        $reader.ReadBytes(16) | Out-Null
        
        # Read date timestamp (8 bytes, big-endian long)
        $date_timestamp = Read-BigEndianInt64 $reader
        
        # Convert milliseconds to DateTime
        $epoch = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
        $date = $epoch.AddMilliseconds($date_timestamp)
        $date_string = $date.ToString("yyMMdd")
        
        # Read bounding box (4 int32s)
        $min_lat_micro = Read-BigEndianInt32 $reader
        $min_lng_micro = Read-BigEndianInt32 $reader
        $max_lat_micro = Read-BigEndianInt32 $reader
        $max_lng_micro = Read-BigEndianInt32 $reader
        
        $min_lat = $min_lat_micro / 1000000.0
        $min_lng = $min_lng_micro / 1000000.0
        $max_lat = $max_lat_micro / 1000000.0
        $max_lng = $max_lng_micro / 1000000.0
        
        $geo_name = Get-GeoName $min_lng $max_lng $min_lat $max_lat
        
        $new_name = "${COUNTRY_CODE}${PRODUCT_CODE}${date_string}${geo_name}"
        $new_path = Join-Path $OUTPUT_DIR "$new_name.map"
        
        Write-Host "Map Details:"
        Write-Host "  Date:        $date_string"
        Write-Host "  Bounding Box: minLat=$min_lat minLng=$min_lng maxLat=$max_lat maxLng=$max_lng"
        Write-Host "  Geo Code:    $geo_name"
        Write-Host "  Generated:   $new_name.map"
        
        $reader.Close()
        $stream.Close()
        
        if (Test-Path $new_path) {
            Remove-Item $new_path -Force
        }
        
        Move-Item $OUTPUT_FILE $new_path
        
        Write-Host ""
    }
    catch {
        Write-Warning "Error processing file: $_"
        if ($reader) { $reader.Close() }
        if ($stream) { $stream.Close() }
    }
}

Write-Host "=========================================="
Write-Host "Done! Processed $($PBF_FILES.Count) files."
Write-Host "=========================================="
