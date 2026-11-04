"""Derive the app's stable signing key from a CI secret, so every build installs as an update.

No key is stored anywhere: the same secret always yields the same EC P-256 key and the same
self-signed certificate (RFC 6979 deterministic signature, fixed dates/serial). Usage:
  IEUM_SIGNING_SEED=<secret> python3 tools/derive_signing_key.py out.p12
If IEUM_SIGNING_SEED is empty the mailbox token IEUM_INBOX_TOKEN is used as the seed (how every build until now was signed),
so copying the current token into the IEUM_SIGNING_SEED secret keeps the same certificate and lets the token change later.
The keystore password is fixed ("ieum-signing"); secrecy comes from the seed. The seed is never printed.
"""
import datetime, hashlib, hmac, os, sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

source = "IEUM_SIGNING_SEED"
seed = os.environ.get(source, "").strip().encode()
if not seed:
    source = "IEUM_INBOX_TOKEN"
    seed = os.environ.get(source, "").strip().encode()
if len(seed) < 20:
    sys.exit("IEUM_SIGNING_SEED missing (and IEUM_INBOX_TOKEN is not usable as the fallback)")
print("signing seed taken from", source)  # the name only, never the value
order = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
scalar = int.from_bytes(hmac.new(seed, b"ieum-app-signing-v1", hashlib.sha256).digest(), "big") % (order - 1) + 1
key = ec.derive_private_key(scalar, ec.SECP256R1())
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "ieum"), x509.NameAttribute(NameOID.ORGANIZATION_NAME, "ieum")])
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(0x1E0F).not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2056, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256(), ecdsa_deterministic=True))
data = pkcs12.serialize_key_and_certificates(b"ieum", key, cert, None,
                                             serialization.BestAvailableEncryption(b"ieum-signing"))
open(sys.argv[1], "wb").write(data)
print("signing certificate sha256:", cert.fingerprint(hashes.SHA256()).hex())
