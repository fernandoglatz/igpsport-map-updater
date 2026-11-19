# Builds Windows release files with a bundled Java runtime into app\target\release\.
# Usage: .\app\package.ps1 [-Type msi,app-image]
#   Type: msi, exe or app-image (app-image is archived as .zip). Default: msi.
# Requires JDK 17+ (jpackage) and Maven; msi/exe also need the WiX Toolset 3 on PATH.
# The version comes from app\pom.xml (the release workflow sets it from the git tag).
param([string[]]$Type = @("msi"))
$ErrorActionPreference = "Stop"

$AppDir = $PSScriptRoot
$Name = "IGPSPORT Map Updater"
$Artifact = "igpsport-map-updater"
$MainClass = "io.github.fernandoglatz.igpsmap.ui.Launcher"
$Modules = "java.base,java.desktop,java.logging,java.xml,java.sql,java.naming,java.net.http,java.management,jdk.management,java.prefs,java.scripting,jdk.unsupported,jdk.crypto.ec,jdk.httpserver,jdk.zipfs"

# Icon made by packaging\make_icon.py.
$Icon = "$AppDir\packaging\icon.ico"
if (-not (Test-Path $Icon)) { throw "Icon not found: $Icon" }

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) { throw "jpackage not found (needs JDK 17+)" }

Write-Host "Building jar and collecting dependencies..."
mvn -q -B -f "$AppDir\pom.xml" -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
$Version = (mvn -q -B -f "$AppDir\pom.xml" help:evaluate "-Dexpression=project.version" -DforceStdout | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $Version) { throw "Could not read the project version" }
$MainJar = "$Artifact-$Version.jar"

$LibDir = "$AppDir\target\libs"
$Dist = "$AppDir\target\dist"
$Release = "$AppDir\target\release"
Copy-Item "$AppDir\target\$MainJar" $LibDir
foreach ($dir in @($Dist, $Release)) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Path $dir | Out-Null
}
$Base = "$Release\$Artifact-$Version-windows-x64"

foreach ($t in $Type) {
    $Out = "$Dist\$t"
    $Extra = @()
    # The upgrade UUID must never change: it lets a new .msi replace the installed version (in-app updates).
    # This is the value jpackage derived for v1.0.0 and v1.1.0.
    if ($t -eq "msi" -or $t -eq "exe") {
        $Extra = @("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-upgrade-uuid", "49a86519-a3be-3472-9700-e505b3df2b60")
    }

    # Keep bin\java.exe in the runtime: osmosis runs in a child JVM started from the bundled runtime.
    Write-Host "Running jpackage (type: $t, version: $Version)..."
    jpackage `
        --type $t `
        --name $Name `
        --app-version $Version `
        --vendor "igpsport-map-updater" `
        --description "Regenerates IGPSPORT cycling computer maps from OpenStreetMap data" `
        --input $LibDir `
        --main-jar $MainJar `
        --main-class $MainClass `
        --java-options "-Xmx1g" `
        --java-options "-Digpsmap.package=$t" `
        --add-modules $Modules `
        --jlink-options "--strip-debug --no-man-pages --no-header-files" `
        --icon $Icon `
        --dest $Out `
        @Extra
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed for type $t" }

    if ($t -eq "app-image") {
        Compress-Archive -Path "$Out\*" -DestinationPath "$Base.zip"
    } else {
        $File = Get-ChildItem -Path $Out -Filter "*.$t" -File | Select-Object -First 1
        if (-not $File) { throw "jpackage produced no .$t file" }
        Copy-Item $File.FullName "$Base.$t"
    }
}

Write-Host "Release files in ${Release}:"
Get-ChildItem $Release
