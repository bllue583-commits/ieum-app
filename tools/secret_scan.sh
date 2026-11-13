#!/usr/bin/env bash
# Secret scan (DESIGN_V7 4.2, r18-lock): gitleaks over the whole git history of this checkout, with .gitleaks.toml
# (gitleaks' default rules plus one documented exception). Writes OUT_JSON:
#   {"findings": n, "tool": "gitleaks 8.24.3", "scope": "history", "commit": "<sha>", "atMs": ms, "rules": {"rule-id": n}}
# Never prints or writes a secret value (--redact=100: every match is replaced; only rule, file, commit and line go out).
# Exit 0: no finding; 1: findings (listed as ::error lines); 2: the scan could not run (download, checksum, tool error).
# Needs the full history (actions/checkout with fetch-depth: 0); a shallow clone scans only what it has and says so.
# Usage: tools/secret_scan.sh OUT_JSON      (GITLEAKS may point at an installed gitleaks binary)
set -u
out="${1:?usage: secret_scan.sh OUT_JSON}"
version=8.24.3
sha=9991e0b2903da4c8f6122b5c3186448b927a5da4deef1fe45271c3793f4ee29c # gitleaks_8.24.3_linux_x64.tar.gz, from its checksums.txt
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
gl="${GITLEAKS:-}"
if [ -z "$gl" ]; then
  curl -fsSL -o "$tmp/gl.tgz" "https://github.com/gitleaks/gitleaks/releases/download/v$version/gitleaks_${version}_linux_x64.tar.gz" \
    || { echo "::warning title=secret scan::gitleaks $version could not be downloaded"; exit 2; }
  echo "$sha  $tmp/gl.tgz" | sha256sum -c --quiet - || { echo "::error title=secret scan::gitleaks download does not match its checksum"; exit 2; }
  tar -xzf "$tmp/gl.tgz" -C "$tmp" gitleaks || exit 2
  gl="$tmp/gitleaks"
fi
scope=history
[ "$(git -C "$root" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ] && { scope=shallow; echo "::warning title=secret scan::shallow clone: only the fetched commits are scanned"; }
"$gl" git --redact=100 --no-banner --exit-code 0 --config "$root/.gitleaks.toml" --report-format json --report-path "$tmp/report.json" "$root" >"$tmp/log.txt" 2>&1 \
  || { tail -5 "$tmp/log.txt"; echo "::warning title=secret scan::gitleaks did not finish"; exit 2; }
python3 - "$tmp/report.json" "$out" "$scope" "$version" "$(git -C "$root" rev-parse HEAD)" <<'EOF'
import json, sys, time
report, out, scope, version, commit = sys.argv[1:]
items = json.load(open(report)) or []
rules = {}
for f in items:
    rules[f.get("RuleID", "?")] = rules.get(f.get("RuleID", "?"), 0) + 1
    print(f"::error title=secret scan::{f.get('RuleID')} in {f.get('File')}:{f.get('StartLine')} (commit {str(f.get('Commit'))[:12]})")
json.dump({"findings": len(items), "tool": "gitleaks " + version, "scope": scope, "commit": commit,
           "atMs": int(time.time() * 1000), "rules": rules}, open(out, "w"))
print(f"secret scan: {len(items)} finding(s), {scope}")
sys.exit(1 if items else 0)
EOF
