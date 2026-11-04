#!/usr/bin/env bash
# Does this commit need the update smoke test (update-smoke.yml) before it reaches the phone? (design H1)
# Prints "true" or "false" on the last line. The base is the commit of the last successful release-apk run,
# i.e. what the phone most likely has, so a risky change is caught even when an earlier release of it failed.
# "true" when, since that commit, any of these changed: an AndroidManifest.xml, IeumApplication, StartupReceiver,
# CallSinkImpl, or the build settings (*.gradle.kts, gradle.properties, the Gradle wrapper).
# When the base cannot be found or compared, it prints "true" (safe side).
# Usage: tools/needs_update_smoke.sh OWNER/REPO SHA   (needs gh with actions:read, run inside the checkout)
set -u
repo="$1"; sha="$2"
risky='(^|/)AndroidManifest\.xml$|/IeumApplication\.kt$|/StartupReceiver\.kt$|/CallSinkImpl\.kt$|(^|/)[^/]*\.gradle\.kts$|^android/gradle\.properties$|^android/gradle/wrapper/'
base=$(gh run list --repo "$repo" --workflow release-apk.yml --status success --limit 1 --json headSha --jq '.[0].headSha // empty' 2>/dev/null)
if [ -z "$base" ]; then echo "no successful release found: smoke test needed"; echo true; exit 0; fi
if ! git cat-file -e "$base^{commit}" 2>/dev/null && ! git fetch --no-tags --depth=1 origin "$base" > /dev/null 2>&1; then
  echo "last released commit $base cannot be fetched: smoke test needed"; echo true; exit 0
fi
if ! changed=$(git diff --name-only "$base" "$sha" 2>/dev/null); then
  echo "cannot compare with $base: smoke test needed"; echo true; exit 0
fi
hits=$(printf '%s\n' "$changed" | grep -E "$risky" || true)
if [ -n "$hits" ]; then
  echo "changed since the last release ($base):"; printf '  %s\n' $hits
  echo true
else
  echo "no start-up or build setting change since the last release ($base)"
  echo false
fi
