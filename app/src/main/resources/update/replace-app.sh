#!/bin/sh
# Replaces a portable IGPSPORT Map Updater folder with a new version and starts it.
# Run by the app right before it exits: replace-app.sh <pid> <app folder> <new folder> <launcher> <staging folder>
# Wait for the app (pid) to exit, swap the folders, start the launcher, clean up.
# Everything (including the new version's start-up output) goes to update.log next to this script.
exec >>"$(dirname "$0")/update.log" 2>&1
echo "$(date '+%Y-%m-%d %H:%M:%S') replacing $2"
# Set by the running app's launcher for itself; a new launcher that inherits it does not start the app.
unset _JPACKAGE_LAUNCHER
pid="$1"
old="$2"
new="$3"
launcher="$4"
staging="$5"

# Wait up to 2 minutes for the app to exit.
tries=0
while kill -0 "$pid" 2>/dev/null; do
    tries=$((tries + 1))
    if [ "$tries" -gt 600 ]; then
        echo "The app did not exit; update not applied." >&2
        exit 1
    fi
    sleep 0.2
done

backup="$old.previous-$$"
if ! mv "$old" "$backup"; then
    echo "Could not move $old; update not applied." >&2
    exit 1
fi
if mv "$new" "$old"; then
    rm -rf "$backup"
else
    echo "Could not move the new version in place; keeping the old one." >&2
    mv "$backup" "$old"
fi
rm -rf "$staging"

echo "$(date '+%Y-%m-%d %H:%M:%S') starting $launcher"
nohup "$launcher" &
exit 0
