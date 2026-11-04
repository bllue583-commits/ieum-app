#!/usr/bin/env bash
# Signing key rotation for the ieum app (design-gap #37, APK Signature Scheme v3 lineage).
#
# Why: the current key comes from IEUM_SIGNING_SEED, or from the mailbox token when that secret is empty, and the token
# is inside every released APK. Anyone holding a leaked APK can rebuild the key and sign a fake update. Rotation moves
# the app to a key whose seed is only in a GitHub secret; the lineage (old key signs the new key) lets installed phones
# accept the new key as an ordinary update, with data kept.
#
# Commands (secrets only from environment variables; nothing secret is ever printed or written outside DIR):
#   rotate_signing_key.sh prepare DIR       old.p12 (IEUM_SIGNING_SEED, or IEUM_INBOX_TOKEN as before), new.p12
#                                           (IEUM_SIGNING_SEED_V2 only) and lineage.bin (old -> new, apksigner rotate)
#   rotate_signing_key.sh sign DIR APK      re-signs APK in place: v2 (Android 8 to 8.1) with the old key, v3 for
#                                           Android 9+ with the new key and the lineage (--rotation-min-sdk-version 28;
#                                           apksigner's own default would rotate only from Android 13)
#   rotate_signing_key.sh check DIR APK PREVIOUS_SHA256
#                                           exit 0 when APK carries the lineage old -> new of DIR and the previous
#                                           release's certificate is the old or the new certificate of that lineage
#   rotate_signing_key.sh certs APK         prints the SHA-256 of every certificate in the APK's lineage, oldest first
#   rotate_signing_key.sh fp APK            the certificate a current phone sees: "Signer #1" of an unrotated APK
#                                           (the same value release-apk always compared), else the newest signer
# APKSIGNER may point at apksigner; default $ANDROID_HOME/build-tools/35.0.0/apksigner. The keystore password is the
# fixed "ieum-signing" of tools/derive_signing_key.py: secrecy comes from the seeds.
#
# What the user does (once, after agreeing to the first rotated release):
#   1. The secret IEUM_SIGNING_SEED must hold the current mailbox token value (design-gap step 5) before rotating:
#      `prepare` refuses the token fallback, because after the token is reissued (#38) the old key, which every
#      rotated release needs for its lineage, could not be made again. Keep that secret forever.
#   2. Make a new random value yourself (at least 32 characters, never written anywhere else) and save it as the
#      repository secret IEUM_SIGNING_SEED_V2. Keep a private copy: losing it means phones can never update again.
#   3. Set the repository variable IEUM_ROTATE to 1 (Settings > Secrets and variables > Actions > Variables).
#      Do it right before a release you can watch: the next release-apk run signs with the lineage, and its
#      certificate check accepts the old or the new certificate. Run update-smoke by hand first with
#      rotate_test=true to see an install over the current release on an emulator.
#   4. Only after the phone has installed the rotated release, reissue the mailbox token (design-gap #38).
#   From then on IEUM_ROTATE stays 1 and both secrets stay set: every release is signed with the lineage.
# Going back:
#   - Before any phone installed a rotated release: set IEUM_ROTATE to 0 (or delete it). Releases are signed with the
#     old key again, exactly as before, same fingerprint.
#   - After a phone installed one: do NOT turn IEUM_ROTATE off. That phone accepts the old key only if the lineage
#     granted rollback, which this script does not grant, so an old-key-only release would not install over it.
#     Fix forward instead (a new release with IEUM_ROTATE=1).
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

cmd="${1:-}"
case "$cmd" in
  prepare)
    dir="${2:?usage: prepare DIR}"; mkdir -p "$dir"
    [ -n "${IEUM_SIGNING_SEED_V2:-}" ] || { echo "IEUM_SIGNING_SEED_V2 is not set: no rotation" >&2; exit 1; }
    # Old key exactly as every release so far; the new key from V2 only. The token fallback only for a rehearsal
    # (update-smoke rotate_test sets IEUM_ROTATE_REHEARSAL=1): a real rotation must not depend on the token.
    [ -n "${IEUM_SIGNING_SEED:-}" ] || [ "${IEUM_ROTATE_REHEARSAL:-}" = "1" ] \
      || { echo "IEUM_SIGNING_SEED is not set: copy the current token into it first (no rotation from the token)" >&2; exit 1; }
    env -u IEUM_SIGNING_SEED_V2 python3 "$here/derive_signing_key.py" "$dir/old.p12" >/dev/null
    python3 "$here/derive_signing_key.py" --seed-env IEUM_SIGNING_SEED_V2 "$dir/new.p12" >/dev/null
    old=$(fp_of_p12 "$dir/old.p12"); new=$(fp_of_p12 "$dir/new.p12")
    [ "$old" != "$new" ] || { echo "IEUM_SIGNING_SEED_V2 gives the same key as the old seed: use a new value" >&2; exit 1; }
    # shellcheck disable=SC2046
    "$apksigner" rotate --out "$dir/lineage.bin" --old-signer $(ks "$dir/old.p12") --new-signer $(ks "$dir/new.p12")
    echo "$old" > "$dir/old.sha256"; echo "$new" > "$dir/new.sha256"
    got=$(lineage_certs "$dir/lineage.bin" | tr '\n' ' ')
    [ "$got" = "$old $new " ] || { echo "lineage does not read back as old -> new ($got)" >&2; exit 1; }
    echo "lineage ready: old $old -> new $new"
    ;;
  sign)
    dir="${2:?usage: sign DIR APK}"; apk="${3:?usage: sign DIR APK}"
    # Keys in order oldest first; --lineage makes the newest the v3 signer for Android 9+.
    # shellcheck disable=SC2046
    "$apksigner" sign $(ks "$dir/old.p12") --next-signer $(ks "$dir/new.p12") --lineage "$dir/lineage.bin" \
      --rotation-min-sdk-version 28 "$apk"
    "$apksigner" verify "$apk" >/dev/null
    got=$(lineage_certs "$apk" | tr '\n' ' ')
    [ "$got" = "$(cat "$dir/old.sha256") $(cat "$dir/new.sha256") " ] || { echo "signed APK lineage is not old -> new ($got)" >&2; exit 1; }
    echo "signed with lineage: $got"
    ;;
  check)
    dir="${2:?usage: check DIR APK PREVIOUS_SHA256}"; apk="${3:?}"; prev="${4:?}"
    old=$(cat "$dir/old.sha256"); new=$(cat "$dir/new.sha256")
    "$apksigner" verify "$apk" >/dev/null || { echo "APK signature does not verify" >&2; exit 1; }
    got=$(lineage_certs "$apk" | tr '\n' ' ')
    [ "$got" = "$old $new " ] || { echo "APK lineage is not old -> new (got: ${got:-none})" >&2; exit 1; }
    case "$prev" in
      "$old") echo "previous release has the old certificate: rotation release ($old -> $new)";;
      "$new") echo "previous release already has the new certificate ($new)";;
      *) echo "previous release certificate $prev is neither the old $old nor the new $new" >&2; exit 1;;
    esac
    ;;
  certs)
    lineage_certs "${2:?usage: certs APK}"
    ;;
  fp)
    # Unrotated: one "Signer #1" line. Rotated: one "Signer (minSdkVersion=.., maxSdkVersion=..)" block per key range;
    # the one open to the newest Android (maxSdkVersion=2147483647) is the current key.
    # Always exit 0, empty when unreadable: callers decide (release-apk only warns for an unreadable previous APK).
    out=$("$apksigner" verify --print-certs "${2:?usage: fp APK}" 2>/dev/null) || true
    v=$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
    [ -n "$v" ] || v=$(printf '%s\n' "$out" | sed -n 's/^Signer (minSdkVersion=[0-9]*, maxSdkVersion=2147483647) certificate SHA-256 digest: //p' | head -n 1)
    echo "$v"
    ;;
  *)
    sed -n '2,20p' "$0" >&2; exit 2;;
esac
