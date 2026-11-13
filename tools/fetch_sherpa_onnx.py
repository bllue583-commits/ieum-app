"""r18-bench (DESIGN_V7 4.4): the engine bench's sherpa-onnx native libraries, from the official release AAR only.

Downloads sherpa-onnx 2.0.1's AAR from the project's GitHub release (or takes IEUM_SHERPA_AAR, a local copy), checks it by
size and SHA-256 (the release's published digest), checks that its classes.jar is the one in third_party/ that the app
compiles against, and puts the two arm64-v8a libraries the JNI needs (libsherpa-onnx-jni.so and its libonnxruntime.so) into
android/app/build/generated/ieum-sherpa with a pin file (assets/local-stt/sherpa-pin.json) that the app checks before it loads
them. arm64-v8a only: on any other ABI the bench reports BENCH_C_NO_RUNTIME. No credentials, no SDK change."""
from pathlib import Path
import hashlib, io, json, os, shutil, sys, urllib.request, zipfile

VERSION = '2.0.1'
URL = f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar'
AAR_BYTES = 49441341
AAR_SHA256 = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
CLASSES_SHA256 = '0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93'
ABI = 'arm64-v8a'
# The JNI library needs only the runtime (readelf NEEDED); the C and C++ API libraries of the AAR are left out.
LIBS = {
    'libonnxruntime.so': (22249560, '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1'),
    'libsherpa-onnx-jni.so': (4609320, '5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575'),
}

root = Path(__file__).resolve().parent.parent
sha = lambda b: hashlib.sha256(b).hexdigest()


def fetch() -> bytes:
    local = os.environ.get('IEUM_SHERPA_AAR')
    cache = root / 'android/app/build/sherpa-onnx' / f'sherpa-onnx-{VERSION}.aar'
    for p in [Path(local)] if local else [cache]:
        if p.is_file():
            data = p.read_bytes()
            if len(data) == AAR_BYTES and sha(data) == AAR_SHA256:
                return data
    with urllib.request.urlopen(URL, timeout=300) as r:
        data = r.read()
    assert len(data) == AAR_BYTES and sha(data) == AAR_SHA256, 'sherpa-onnx AAR size/sha256 mismatch'
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(data)
    return data


def main():
    data = fetch()
    out = root / 'android/app/build/generated/ieum-sherpa'
    if out.exists():
        shutil.rmtree(out)
    libs = out / 'jniLibs' / ABI
    libs.mkdir(parents=True)
    pins = {}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        assert all(not n.startswith('/') and '..' not in Path(n).parts for n in names)
        jar = z.read('classes.jar')
        assert sha(jar) == CLASSES_SHA256, 'classes.jar differs from third_party/sherpa-onnx-2.0.1'
        assert sha((root / 'third_party/sherpa-onnx-2.0.1/sherpa-onnx-2.0.1-classes.jar').read_bytes()) == CLASSES_SHA256
        for name, (size, digest) in LIBS.items():
            b = z.read(f'jni/{ABI}/{name}')
            assert len(b) == size and sha(b) == digest, f'{name} size/sha256 mismatch'
            (libs / name).write_bytes(b)
            pins[name] = {'sha256': digest, 'bytes': size}
    assets = out / 'assets/local-stt'
    assets.mkdir(parents=True)
    meta = {'schema': 'ieum.sherpa-onnx-runtime.v1', 'engine': f'sherpa-onnx-{VERSION}-android', 'source': URL,
            'aarSha256': AAR_SHA256, 'native': {ABI: pins}, 'network': False, 'microphone': False}
    (assets / 'sherpa-pin.json').write_text(json.dumps(meta, indent=2) + '\n')
    print(json.dumps({'status': 'SHERPA_ONNX_READY', 'abi': ABI, 'libs': pins}), flush=True)


if __name__ == '__main__':
    sys.exit(main())
