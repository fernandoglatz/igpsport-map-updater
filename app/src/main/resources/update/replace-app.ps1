# Replaces a portable IGPSPORT Map Updater folder with a new version and starts it.
# Run by the app right before it exits.
param(
    [int]$ProcessId,
    [string]$Old,
    [string]$New,
    [string]$Launcher,
    [string]$Staging
)

# Set by the running app's launcher for itself; a new launcher that inherits it does not start the app.
Remove-Item Env:_JPACKAGE_LAUNCHER -ErrorAction SilentlyContinue

# Wait for the app to exit (up to 2 minutes), then give Windows a moment to release its files.
$deadline = (Get-Date).AddMinutes(2)
while (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) { exit 1 }
    Start-Sleep -Milliseconds 200
}
Start-Sleep -Milliseconds 500

$backup = "$Old.previous-$PID"
try {
    Move-Item -LiteralPath $Old -Destination $backup -ErrorAction Stop
} catch {
    exit 1
}
try {
    Move-Item -LiteralPath $New -Destination $Old -ErrorAction Stop
    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    Move-Item -LiteralPath $backup -Destination $Old
}
Remove-Item -LiteralPath $Staging -Recurse -Force -ErrorAction SilentlyContinue

Start-Process -FilePath $Launcher
