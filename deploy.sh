#!/bin/sh
# Builds the mod and installs it into a Modrinth App profile, replacing any older NyAddOns jar.
# Usage: ./deploy.sh ["Profile Name"]      (default: SkyBlock Enhanced)
# Restart the instance afterwards: Minecraft only loads mods at startup.
set -e
cd "$(dirname "$0")"

PROFILE="${1:-SkyBlock Enhanced}"
MODS="$HOME/Library/Application Support/ModrinthApp/profiles/$PROFILE/mods"

if [ ! -d "$MODS" ]; then
	echo "No mods folder for profile \"$PROFILE\": $MODS" >&2
	exit 1
fi

./gradlew build --quiet

VERSION=$(sed -n 's/^version=//p' gradle.properties)
JAR="build/libs/NyAddOns-$VERSION.jar"

# Copy next to the target, then rename over it, so a running game keeps its old file intact.
find "$MODS" -maxdepth 1 -name 'NyAddOns-*.jar' ! -name "$(basename "$JAR")" -delete
cp "$JAR" "$MODS/.nyaddons.tmp"
mv "$MODS/.nyaddons.tmp" "$MODS/$(basename "$JAR")"

echo "Installed $(basename "$JAR") into \"$PROFILE\". Restart the instance to load it."
