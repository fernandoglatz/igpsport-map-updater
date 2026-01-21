#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Download and extract osmosis if not present
OSMOSIS_VERSION="0.49.2"
OSMOSIS_DIR="$SCRIPT_DIR/osmosis-$OSMOSIS_VERSION"

if [ ! -d "$OSMOSIS_DIR" ]; then
    echo "Osmosis not found. Downloading osmosis-$OSMOSIS_VERSION..."
    OSMOSIS_URL="https://github.com/openstreetmap/osmosis/releases/download/$OSMOSIS_VERSION/osmosis-$OSMOSIS_VERSION.zip"
    OSMOSIS_ZIP="$SCRIPT_DIR/osmosis-$OSMOSIS_VERSION.zip"
    
    curl -sL -o "$OSMOSIS_ZIP" "$OSMOSIS_URL"
    
    echo "Extracting osmosis..."
    unzip -q "$OSMOSIS_ZIP" -d "$SCRIPT_DIR"
    
    echo "Cleaning up..."
    rm "$OSMOSIS_ZIP"
    
    # Make osmosis executable
    chmod +x "$OSMOSIS_DIR/bin/osmosis"
    
    echo "Osmosis $OSMOSIS_VERSION installed successfully."
    echo ""
fi

# Download and install Mapsforge writer plugin if not present
MAPSFORGE_WRITER_VERSION="0.25.0"
MAPSFORGE_WRITER_JAR="$OSMOSIS_DIR/lib/mapsforge-map-writer-${MAPSFORGE_WRITER_VERSION}-jar-with-dependencies.jar"

if [ ! -f "$MAPSFORGE_WRITER_JAR" ]; then
    echo "Mapsforge writer plugin not found. Downloading version $MAPSFORGE_WRITER_VERSION..."
    MAPSFORGE_URL="https://repo1.maven.org/maven2/org/mapsforge/mapsforge-map-writer/${MAPSFORGE_WRITER_VERSION}/mapsforge-map-writer-${MAPSFORGE_WRITER_VERSION}-jar-with-dependencies.jar"
    
    curl -sL -o "$MAPSFORGE_WRITER_JAR" "$MAPSFORGE_URL"
    
    echo "Mapsforge writer plugin installed successfully."
    echo ""
fi

# Create wrapper script that includes Mapsforge in classpath
OSMOSIS_WRAPPER="$OSMOSIS_DIR/bin/osmosis-with-mapsforge"
if [ ! -f "$OSMOSIS_WRAPPER" ]; then
    # Modify the osmosis script to include Mapsforge in CLASSPATH
    MAPSFORGE_JAR_NAME="mapsforge-map-writer-${MAPSFORGE_WRITER_VERSION}-jar-with-dependencies.jar"
    cp "$OSMOSIS_DIR/bin/osmosis" "$OSMOSIS_WRAPPER"
    
    # Add Mapsforge JAR to the CLASSPATH line in the wrapper
    sed -i "s|^CLASSPATH=\$APP_HOME|CLASSPATH=\$APP_HOME/lib/$MAPSFORGE_JAR_NAME:\$APP_HOME|" "$OSMOSIS_WRAPPER"
    
    chmod +x "$OSMOSIS_WRAPPER"
fi

TAG_CONF_FILE="$SCRIPT_DIR/tag-igpsport.xml"
TAG_TRANSFORM_FILE="$SCRIPT_DIR/tag-igpsport-transform.xml"
THREADS=4
TMP_DIR="$SCRIPT_DIR/tmp"
export JAVA_OPTS="-Xms1g -Xmx8g -Djava.io.tmpdir=$TMP_DIR"
export CLASSPATH="$OSMOSIS_DIR/lib/mapsforge-map-writer-${MAPSFORGE_WRITER_VERSION}-jar-with-dependencies.jar:$CLASSPATH"

# Create directories
DOWNLOAD_DIR="$SCRIPT_DIR/download"
OUTPUT_DIR="$SCRIPT_DIR/output"

mkdir -p "$TMP_DIR"
mkdir -p "$DOWNLOAD_DIR"
mkdir -p "$OUTPUT_DIR"

# Check if maps.csv exists
CSV_FILE="$SCRIPT_DIR/maps.csv"
if [ ! -f "$CSV_FILE" ]; then
    echo "ERROR: maps.csv not found in directory: $SCRIPT_DIR" >&2
    exit 1
fi

# Read CSV file and download files
echo "Reading maps.csv..."
declare -a PBF_FILES=()
declare -a POLY_FILES=()
declare -a ORIGINAL_NAMES=()

line_num=0
while IFS=',' read -r original_name pbf_url poly_url; do
    line_num=$((line_num + 1))
    
    # Skip header line
    if [ $line_num -eq 1 ]; then
        continue
    fi
    
    # Skip empty lines
    if [ -z "$original_name" ]; then
        continue
    fi
    
    echo ""
    echo "Processing entry: $original_name"
    
    # Download PBF file
    pbf_filename=$(basename "$pbf_url")
    pbf_path="$DOWNLOAD_DIR/$pbf_filename"
    
    if [ ! -f "$pbf_path" ]; then
        echo "  Downloading PBF: $pbf_filename..."
        curl -sL -o "$pbf_path" "$pbf_url"
        echo "  PBF downloaded."
    else
        echo "  PBF already exists: $pbf_filename"
    fi
    
    # Download Poly file
    poly_filename=$(basename "$poly_url")
    poly_path="$DOWNLOAD_DIR/$poly_filename"
    
    if [ ! -f "$poly_path" ]; then
        echo "  Downloading Poly: $poly_filename..."
        curl -sL -o "$poly_path" "$poly_url"
        echo "  Poly downloaded."
    else
        echo "  Poly already exists: $poly_filename"
    fi
    
    PBF_FILES+=("$pbf_path")
    POLY_FILES+=("$poly_path")
    ORIGINAL_NAMES+=("$original_name")
    
done < "$CSV_FILE"

if [ ${#PBF_FILES[@]} -eq 0 ]; then
    echo "ERROR: No entries found in maps.csv" >&2
    exit 1
fi

echo ""
echo "=========================================="
echo "Found ${#PBF_FILES[@]} entries to process"
echo "=========================================="
echo ""

if [ ! -f "$TAG_CONF_FILE" ]; then
    echo "ERROR: Tag configuration file not found: $TAG_CONF_FILE" >&2
    exit 1
fi

if [ ! -f "$TAG_TRANSFORM_FILE" ]; then
    echo "ERROR: Tag transform file not found: $TAG_TRANSFORM_FILE" >&2
    exit 1
fi

MAGIC_STRING="mapsforge binary OSM"
DEFAULT_ZOOM=13
ZOOM=$((1 << DEFAULT_ZOOM))
BASE36_CHARS="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

read_big_endian_long() {
    local bytes=""
    for i in {1..8}; do
        IFS= read -r -n1 byte
        printf -v byte_val '%d' "'$byte"
        bytes="$bytes $byte_val"
    done
    
    local result=0
    for b in $bytes; do
        result=$(( (result << 8) + b ))
    done
    echo "$result"
}

read_big_endian_int32() {
    local bytes=""
    for i in {1..4}; do
        IFS= read -r -n1 byte
        if [ -z "$byte" ]; then
            echo "ERROR: EOF while reading int32" >&2
            return 1
        fi
        printf -v byte_val '%d' "'$byte"
        bytes="$bytes $byte_val"
    done
    
    local arr=($bytes)
    local u=$(( (${arr[0]} << 24) | (${arr[1]} << 16) | (${arr[2]} << 8) | ${arr[3]} ))
    
    if [ $u -ge 2147483648 ]; then
        echo $(( u - 4294967296 ))
    else
        echo $u
    fi
}

convert_to_base36() {
    local value=$1
    local length=$2
    
    if [ $value -lt 0 ]; then
        value=0
    fi
    
    local result=""
    for (( i=0; i<length; i++ )); do
        result="${BASE36_CHARS:$((value % 36)):1}$result"
        value=$((value / 36))
    done
    
    echo "$result"
}

convert_to_tile_x() {
    local lon=$1
    local tiles_per_side=$2
    echo "scale=10; ((($lon + 180.0) / 360.0) * $tiles_per_side)" | bc | awk '{printf("%d\n", $1)}'
}

convert_to_tile_y() {
    local lat=$1
    local tiles_per_side=$2
    echo "scale=10; ((1.0 - (l((1.0 + s($lat * 3.141592653589793 / 180.0)) / (1.0 - s($lat * 3.141592653589793 / 180.0))) / 2.0) / 3.141592653589793) / 2.0) * $tiles_per_side" | bc -l | awk '{printf("%d\n", $1)}'
}

get_geo_name() {
    local min_lng=$1
    local max_lng=$2
    local min_lat=$3
    local max_lat=$4
    
    local x_start=$(convert_to_tile_x "$min_lng" "$ZOOM")
    local y_start=$(convert_to_tile_y "$max_lat" "$ZOOM")
    local x_end=$(convert_to_tile_x "$max_lng" "$ZOOM")
    local y_end=$(convert_to_tile_y "$min_lat" "$ZOOM")
    
    local x_span=$((x_end - x_start + 1))
    local y_span=$((y_end - y_start + 1))
    
    echo "$(convert_to_base36 $x_start 3)$(convert_to_base36 $y_start 3)$(convert_to_base36 $((x_span - 1)) 3)$(convert_to_base36 $((y_span - 1)) 3)"
}

file_index=0
for i in "${!PBF_FILES[@]}"; do
    file_index=$((file_index + 1))
    INPUT_FILE="${PBF_FILES[$i]}"
    POLY_FILE="${POLY_FILES[$i]}"
    ORIGINAL_NAME="${ORIGINAL_NAMES[$i]}"
    file_name=$(basename "$INPUT_FILE")
    
    # Extract country code from original filename (first 2 characters)
    COUNTRY_CODE="${ORIGINAL_NAME:0:2}"
    
    # Extract product code from original filename (characters 2-5, 0-indexed)
    PRODUCT_CODE="${ORIGINAL_NAME:2:4}"
    
    echo "=========================================="
    echo "Processing [$file_index/${#PBF_FILES[@]}]"
    echo "  PBF File:      $file_name"
    echo "  Poly File:     $(basename "$POLY_FILE")"
    echo "  Original Name: $ORIGINAL_NAME"
    echo "  Country Code:  $COUNTRY_CODE"
    echo "  Product Code:  $PRODUCT_CODE"
    echo "=========================================="
    
    OUTPUT_FILE="$OUTPUT_DIR/out_$file_index.map"
    
    echo "Running osmosis..."
    "$OSMOSIS_DIR/bin/osmosis-with-mapsforge" \
        --read-pbf-fast file="$INPUT_FILE" \
        --bounding-polygon file="$POLY_FILE" \
        --tag-transform file="$TAG_TRANSFORM_FILE" \
        --mapfile-writer file="$OUTPUT_FILE" type=hd zoom-interval-conf=13,13,13,14,14,14 threads=$THREADS tag-conf-file="$TAG_CONF_FILE"
    
    if [ ! -f "$OUTPUT_FILE" ]; then
        echo "WARNING: Osmosis did not generate file for: $file_name - skipping"
        continue
    fi
    
    echo "Osmosis completed. Generating name..."
    
    {
        # Read magic string
        magic=$(dd bs=1 count=${#MAGIC_STRING} 2>/dev/null)
        
        if [ "$magic" != "$MAGIC_STRING" ]; then
            echo "WARNING: Invalid .map file for: $file_name - skipping"
            continue
        fi
        
        # Skip 16 bytes (4 + 4 + 8)
        dd bs=1 count=16 2>/dev/null >/dev/null
        
        # Read date timestamp (8 bytes, big-endian long)
        date_bytes=""
        for i in {1..8}; do
            byte=$(dd bs=1 count=1 2>/dev/null | od -An -td1 | tr -d ' ')
            if [ -z "$byte" ]; then byte=0; fi
            date_bytes="$date_bytes $byte"
        done
        
        date_timestamp=0
        for b in $date_bytes; do
            date_timestamp=$(( (date_timestamp << 8) + (b < 0 ? b + 256 : b) ))
        done
        
        # Convert milliseconds to seconds for date command
        date_seconds=$((date_timestamp / 1000))
        date_string=$(date -d "@$date_seconds" "+%y%m%d")
        
        # Read bounding box (4 int32s)
        read_int32() {
            local bytes=""
            for i in {1..4}; do
                byte=$(dd bs=1 count=1 2>/dev/null | od -An -td1 | tr -d ' ')
                if [ -z "$byte" ]; then byte=0; fi
                bytes="$bytes $byte"
            done
            
            local arr=($bytes)
            for i in 0 1 2 3; do
                if [ ${arr[$i]} -lt 0 ]; then
                    arr[$i]=$((${arr[$i]} + 256))
                fi
            done
            
            local u=$(( (${arr[0]} << 24) | (${arr[1]} << 16) | (${arr[2]} << 8) | ${arr[3]} ))
            
            if [ $u -ge 2147483648 ]; then
                echo $(( u - 4294967296 ))
            else
                echo $u
            fi
        }
        
        min_lat_micro=$(read_int32)
        min_lng_micro=$(read_int32)
        max_lat_micro=$(read_int32)
        max_lng_micro=$(read_int32)
        
        min_lat=$(echo "scale=6; $min_lat_micro / 1000000.0" | bc)
        min_lng=$(echo "scale=6; $min_lng_micro / 1000000.0" | bc)
        max_lat=$(echo "scale=6; $max_lat_micro / 1000000.0" | bc)
        max_lng=$(echo "scale=6; $max_lng_micro / 1000000.0" | bc)
        
        geo_name=$(get_geo_name "$min_lng" "$max_lng" "$min_lat" "$max_lat")
        
        new_name="${COUNTRY_CODE}${PRODUCT_CODE}${date_string}${geo_name}"
        new_path="$OUTPUT_DIR/$new_name.map"
        
        echo "Map Details:"
        echo "  Date:        $date_string"
        echo "  Bounding Box: minLat=$min_lat minLng=$min_lng maxLat=$max_lat maxLng=$max_lng"
        echo "  Geo Code:    $geo_name"
        echo "  Generated:   $new_name.map"
        
        if [ -f "$new_path" ]; then
            rm -f "$new_path"
        fi
        
        mv "$OUTPUT_FILE" "$new_path"
        
        echo ""
    } < "$OUTPUT_FILE"
done

echo "=========================================="
echo "Done! Processed ${#PBF_FILES[@]} files."
echo "=========================================="
