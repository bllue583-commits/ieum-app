#!/usr/bin/env bash
# Signing key rotation for the ieum app (DESIGN_V7 4.2, r18-lock: "the lineage is made once and kept").
#
# Why: the current key comes from IEUM_SIGNING_SEED, or from the mailbox token when that secret is empty (it is: the user
# never kept the token value), and the token is inside every released APK. Anyone holding a leaked APK can rebuild the key
# and sign a fake update. Rotation moves the app to a key whose seed is only in the secret IEUM_SIGNING_SEED_V2. The lineage
# (the old key signs the new key's certificate, APK Signature Scheme v3) lets installed phones take the new key as an
# ordinary update, data kept.
#
# The lineage is made ONCE (the first rotated release, while the old key can still be derived in CI) and kept in the
# mailbox repository as app/signing-lineage.bin (certificates and signatures only, no key: not a secret). Every later
# release is signed with the new key and that stored lineage only, so the old key, and with it the token, is never needed
# again: after the first rotated release is on the phone the token can be reissued.
#
# Commands (seeds only from environment variables; nothing secret is printed or written outside DIR; old.p12 never
# outlives the command that made it):
#   rotate_signing_key.sh newkey DIR           DIR/new.p12 from IEUM_SIGNING_SEED_V2 only (no fallback), DIR/new.sha256
#   rotate_signing_key.sh lineage DIR OUT      first rotation only: the old key exactly as every release so far
#                                              (IEUM_SIGNING_SEED, or IEUM_INBOX_TOKEN while that secret is empty), the new
#                                              key, `apksigner rotate` -> OUT (old -> new); old.p12 deleted at once
#   rotate_signing_key.sh sign DIR LINEAGE APK re-signs APK in place with DIR/new.p12 and LINEAGE only: v3 only
#                                              (--rotation-min-sdk-version 28), see "Android 8" below
#   rotate_signing_key.sh check LINEAGE NEW_SHA256 PREVIOUS_APK_OR_EMPTY [VERSION_JSON]
#                                              exit 0 when LINEAGE is old -> ... -> NEW_SHA256 and chains to the previous
#                                              release: an unrotated previous APK carries the lineage's first certificate;
#                                              a rotated one carries this very lineage. Without the previous APK, from
#                                              version.json (lineageSha256 = this file, or certSha256 = the first certificate)
#   rotate_signing_key.sh certs APK_OR_LINEAGE the SHA-256 of every certificate in the lineage, oldest first (empty: none)
#   rotate_signing_key.sh fp APK               the certificate a current phone sees: "Signer #1" of an unrotated APK
#                                              (the same value release-apk always compared), else the newest signer
# APKSIGNER may point at apksigner; default $ANDROID_HOME/build-tools/35.0.0/apksigner. The keystore password is the
# fixed "ieum-signing" of tools/derive_signing_key.py: secrecy comes from the seeds.
#
# Android 8 (checked with build-tools 35 apksigner, 11/13): apksigner signs v1 and v2 with the OLDEST signer of a lineage
# and refuses "new signer + --lineage" while v1 or v2 is on ("the oldest signer in the SigningCertificateLineage is
# missing"). Keeping Android 8.0 to 8.1 (v2 only) would need the old key on every build, i.e. the token forever. So a
# rotated APK carries v3 only: Android 9+ installs it, Android 8.x cannot (it has no v3). minSdk stays 26 in the build
# (the unrotated path is byte-for-byte what it was); the only phone in use runs Android 16.
#
# What the user does (docs/OPERATIONS.md "서명 키 교체" has the same steps in Korean, with links):
#   1. Make a new random value (at least 32 characters) and save it as the repository secret IEUM_SIGNING_SEED_V2.
#      Keep a private copy: losing it after the rotation means the phone can never update again (reinstall by hand).
#   2. Set the repository variable IEUM_ROTATE to 1 (Settings > Secrets and variables > Actions > Variables).
#   3. The next release-apk run makes the lineage, signs with it, puts app/signing-lineage.bin into the mailbox and
#      writes seedIndependent=true into app/version.json. Check that the phone installed it (heartbeat versionCode).
#   4. Only then reissue the mailbox token (with an expiry date), put it into IEUM_INBOX_TOKEN, release, check the phone
#      took that build, then revoke the old token.
# Going back:
#   - Before the phone installed a rotated release: set IEUM_ROTATE to 0 (or delete it). Releases are signed with the old
#     key again, exactly as before, same fingerprint. Delete app/signing-lineage.bin only if you will never rotate with the
#     same IEUM_SIGNING_SEED_V2 (keeping it is harmless).
#   - After the phone installed one: do NOT turn IEUM_ROTATE off. The lineage grants no rollback, so an old-key-only
#     release would not install over it. Fix forward instead (a new release with IEUM_ROTATE=1).
# Losing app/signing-lineage.bin:
#   - While the token the old key comes from is unchanged (or IEUM_SIGNING_SEED is set): nothing is lost. The next run
#     makes the lineage again from the same two keys (same certificates, so `check` passes) and stores it again.
#   - After the token was reissued: the old key is gone, the lineage cannot be made again: the next run would make one
#     from the new token's key, and `check` stops that release (its first certificate is not the previous release's). Every rotated release in the mailbox carries a second copy as its asset
#     signing-lineage.bin: put that back as app/signing-lineage.bin. (`apksigner lineage --in <apk> --out` does not write
#     a file without a change, checked 11/13, so the APK itself is no copy.)
set -euo pipefail

apksigner="${APKSIGNER:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/build-tools/35.0.0/apksigner}"
here="$(cd "$(dirname "$0")" && pwd)"
ks() { echo --ks "$1" --ks-type PKCS12 --ks-key-alias ieum --ks-pass pass:ieum-signing --key-pass pass:ieum-signing; }
fp_of_p12() { python3 -c "import sys
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.serialization import pkcs12
k, c, _ = pkcs12.load_key_and_certificates(open(sys.argv[1], 'rb').read(), b'ieum-signing')
print(c.fingerprint(hashes.SHA256()).hex())" "$1"; }
lineage_certs() { { "$apksigner" lineage --in "$1" --print-certs 2>/dev/null || true; } | sed -n 's/^Signer #[0-9]* in lineage certificate SHA-256 digest: //p'; }
json_field() { python3 -c "import json,sys
try: print(json.load(open(sys.argv[1])).get(sys.argv[2]) or '')
except Exception: print('')" "$1" "$2"; }

cmd="${1:-}"
case "$cmd" in
  newkey)
    dir="${2:?usage: newkey DIR}"; mkdir -p "$dir"
    [ -n "${IEUM_SIGNING_SEED_V2:-}" ] || { echo "IEUM_SIGNING_SEED_V2 is not set: no rotation" >&2; exit 1; }
    [ "${#IEUM_SIGNING_SEED_V2}" -ge 32 ] || { echo "IEUM_SIGNING_SEED_V2 is shorter than 32 characters: make a longer random value" >&2; exit 1; }
    python3 "$here/derive_signing_key.py" --seed-env IEUM_SIGNING_SEED_V2 "$dir/new.p12" >/dev/null
    fp_of_p12 "$dir/new.p12" > "$dir/new.sha256"
    echo "new key: $(cat "$dir/new.sha256")"
    ;;
  lineage)
    dir="${2:?usage: lineage DIR OUT}"; out="${3:?usage: lineage DIR OUT}"
    [ -f "$dir/new.p12" ] || { echo "run newkey first" >&2; exit 1; }
    # The old key exactly as every release so far: IEUM_SIGNING_SEED, or the token while that secret is empty.
    trap 'rm -f "$dir/old.p12"' EXIT
    env -u IEUM_SIGNING_SEED_V2 python3 "$here/derive_signing_key.py" "$dir/old.p12" >/dev/null
    old=$(fp_of_p12 "$dir/old.p12"); new=$(cat "$dir/new.sha256")
    [ "$old" != "$new" ] || { echo "IEUM_SIGNING_SEED_V2 gives the same key as the old seed: use a new value" >&2; exit 1; }
    # shellcheck disable=SC2046
    "$apksigner" rotate --out "$out" --old-signer $(ks "$dir/old.p12") --new-signer $(ks "$dir/new.p12")
    rm -f "$dir/old.p12"
    got=$(lineage_certs "$out" | tr '\n' ' ')
    [ "$got" = "$old $new " ] || { echo "lineage does not read back as old -> new ($got)" >&2; exit 1; }
    echo "lineage made: old $old -> new $new"
    ;;
  sign)
    dir="${2:?usage: sign DIR LINEAGE APK}"; lin="${3:?usage: sign DIR LINEAGE APK}"; apk="${4:?usage: sign DIR LINEAGE APK}"
    new=$(cat "$dir/new.sha256")
    want=$(lineage_certs "$lin" | tr '\n' ' ')
    [ "$(echo "$want" | wc -w)" -ge 2 ] || { echo "lineage $lin is unreadable or has fewer than two certificates" >&2; exit 1; }
    [ "$(echo "$want" | awk '{print $NF}')" = "$new" ] || { echo "lineage does not end at the new key ($new): wrong IEUM_SIGNING_SEED_V2 or lineage" >&2; exit 1; }
    # v3 only (see "Android 8" above); --rotation-min-sdk-version 28: apksigner's own default would rotate only from Android 13.
    # shellcheck disable=SC2046
    "$apksigner" sign $(ks "$dir/new.p12") --lineage "$lin" --v1-signing-enabled false --v2-signing-enabled false \
      --v3-signing-enabled true --rotation-min-sdk-version 28 "$apk"
    "$apksigner" verify --min-sdk-version 28 "$apk" >/dev/null
    got=$(lineage_certs "$apk" | tr '\n' ' ')
    [ "$got" = "$want" ] || { echo "signed APK lineage is not the stored lineage ($got)" >&2; exit 1; }
    [ "$(bash "$0" fp "$apk")" = "$new" ] || { echo "signed APK's signer is not the new key" >&2; exit 1; }
    echo "signed with the new key and the stored lineage: $got"
    ;;
  check)
    lin="${2:?usage: check LINEAGE NEW_SHA256 PREVIOUS_APK_OR_EMPTY [VERSION_JSON]}"; new="${3:?}"; prev="${4:-}"; vj="${5:-}"
    certs=$(lineage_certs "$lin" | tr '\n' ' ')
    first=$(echo "$certs" | awk '{print $1}'); last=$(echo "$certs" | awk '{print $NF}')
    [ -n "$first" ] && [ "$first" != "$last" ] || { echo "lineage is unreadable or has one certificate" >&2; exit 1; }
    [ "$last" = "$new" ] || { echo "lineage ends at $last, not at the new key $new" >&2; exit 1; }
    if [ -n "$prev" ] && [ -s "$prev" ]; then
      "$apksigner" verify "$prev" >/dev/null 2>&1 || { echo "previous APK does not verify" >&2; exit 1; }
      prev_lin=$(lineage_certs "$prev" | tr '\n' ' ')
      if [ -z "$prev_lin" ]; then
        prev_fp=$(bash "$0" fp "$prev")
        [ "$prev_fp" = "$first" ] || { echo "previous release ($prev_fp) is not the lineage's first certificate ($first)" >&2; exit 1; }
        echo "first rotated release: previous release has the old certificate $first"
      else
        [ "$prev_lin" = "$certs" ] || { echo "previous release carries another lineage ($prev_lin) than this one ($certs)" >&2; exit 1; }
        echo "previous release carries the same lineage ($certs)"
      fi
      exit 0
    fi
    [ -n "$vj" ] && [ -s "$vj" ] || { echo "previous release unknown (no APK, no version.json): not comparing is not allowed while rotating" >&2; exit 1; }
    vl=$(json_field "$vj" lineageSha256); vc=$(json_field "$vj" certSha256)
    if [ -n "$vl" ]; then
      [ "$vl" = "$(sha256sum "$lin" | cut -d' ' -f1)" ] || { echo "version.json lineageSha256 $vl is not this lineage file" >&2; exit 1; }
      echo "previous release (version.json) used this lineage file"
    else
      [ "$vc" = "$first" ] || { echo "version.json certSha256 (${vc:-none}) is not the lineage's first certificate ($first)" >&2; exit 1; }
      echo "first rotated release (version.json): previous certificate $first"
    fi
    ;;
  certs)
    lineage_certs "${2:?usage: certs APK_OR_LINEAGE}"
    ;;
  fp)
    # Unrotated: one "Signer #1" line. Rotated: "Signer #1" is the newest key too (v3 only), or, for an APK signed by the
    # older two-key scheme, one "Signer (minSdkVersion=.., maxSdkVersion=..)" block per key range: the one open to the newest
    # Android (maxSdkVersion=2147483647) is the current key.
    # Always exit 0, empty when unreadable: callers decide (release-apk only warns for an unreadable previous APK).
    out=$("$apksigner" verify --print-certs "${2:?usage: fp APK}" 2>/dev/null) || true
    v=$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
    [ -n "$v" ] || v=$(printf '%s\n' "$out" | sed -n 's/^Signer (minSdkVersion=[0-9]*, maxSdkVersion=2147483647) certificate SHA-256 digest: //p' | head -n 1)
    echo "$v"
    ;;
  *)
    sed -n '2,40p' "$0" >&2; exit 2;;
esac
