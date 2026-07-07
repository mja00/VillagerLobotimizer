#!/usr/bin/env bash
# Publishes the built plugin jar to CurseForge via the upload API.
#
# CurseForge project 1601723 only accepts game versions from the legacy flat
# "Minecraft" version type (typeID 1), which the CurseForgeGradle plugin cannot
# emit (it filters to minecraft-*/modloader types), so we resolve IDs and upload
# directly.
#
# Required env: CURSEFORGE_TOKEN, CURSEFORGE_GAME_VERSIONS (comma list), CURSEFORGE_JAR
# Optional env: CURSEFORGE_PROJECT_ID (default 1601723), CURSEFORGE_RELEASE_TYPE
#               (default beta), CURSEFORGE_DISPLAY_NAME, CURSEFORGE_CHANGELOG
set -euo pipefail

: "${CURSEFORGE_TOKEN:?CURSEFORGE_TOKEN is required}"
: "${CURSEFORGE_GAME_VERSIONS:?CURSEFORGE_GAME_VERSIONS is required (comma-separated names)}"
: "${CURSEFORGE_JAR:?CURSEFORGE_JAR is required (path to the jar)}"
PROJECT_ID="${CURSEFORGE_PROJECT_ID:-1601723}"
RELEASE_TYPE="${CURSEFORGE_RELEASE_TYPE:-beta}"

API="https://minecraft.curseforge.com"

if [ ! -f "$CURSEFORGE_JAR" ]; then
  echo "Jar not found: $CURSEFORGE_JAR" >&2
  exit 1
fi

# Resolve requested version names to CurseForge's legacy type-1 IDs; fail loudly if any is missing.
versions_json=$(mktemp)
curl -fsS "$API/api/game/versions?token=$CURSEFORGE_TOKEN" > "$versions_json"
ids=$(CURSEFORGE_VERSIONS_JSON="$versions_json" python3 - <<'PY'
import json, os, sys
data = json.load(open(os.environ["CURSEFORGE_VERSIONS_JSON"]))
by_name = {v["name"]: v["id"] for v in data if v.get("gameVersionTypeID") == 1}
wanted = [s.strip() for s in os.environ["CURSEFORGE_GAME_VERSIONS"].split(",") if s.strip()]
missing = [w for w in wanted if w not in by_name]
if missing:
    sys.stderr.write(f"Game versions not found under CurseForge type 1: {missing}\n")
    sys.exit(1)
print(",".join(str(by_name[w]) for w in wanted))
PY
)
rm -f "$versions_json"
echo "Resolved game version IDs: $ids"

metadata=$(CURSEFORGE_IDS="$ids" CURSEFORGE_RELEASE_TYPE="$RELEASE_TYPE" python3 - <<'PY'
import json, os
meta = {
    "changelog": os.environ.get("CURSEFORGE_CHANGELOG", ""),
    "changelogType": "markdown",
    "releaseType": os.environ["CURSEFORGE_RELEASE_TYPE"],
    "gameVersions": [int(x) for x in os.environ["CURSEFORGE_IDS"].split(",")],
}
name = os.environ.get("CURSEFORGE_DISPLAY_NAME")
if name:
    meta["displayName"] = name
print(json.dumps(meta))
PY
)

echo "Uploading $CURSEFORGE_JAR to CurseForge project $PROJECT_ID ($RELEASE_TYPE)"
resp_body=$(mktemp)
http_code=$(curl -sS -o "$resp_body" -w '%{http_code}' \
  -X POST "$API/api/projects/$PROJECT_ID/upload-file?token=$CURSEFORGE_TOKEN" \
  -F "metadata=$metadata" -F "file=@$CURSEFORGE_JAR")

if [ "$http_code" != "200" ]; then
  echo "CurseForge upload failed (HTTP $http_code): $(cat "$resp_body")" >&2
  rm -f "$resp_body"
  exit 1
fi

file_id=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['id'])" "$resp_body")
rm -f "$resp_body"
echo "Published CurseForge file id $file_id"
