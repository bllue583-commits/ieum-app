"""Verify a locally supplied public Robolectric JAR and copy to generated test assets."""
from pathlib import Path
import argparse, hashlib, shutil
parser=argparse.ArgumentParser()
parser.add_argument('jar',type=Path)
a=parser.parse_args()
want='06c4c8602d6db7486266a982bc55834ed6dfebd62a0b88daca204139ecf142cb'
if a.jar.stat().st_size != 200643142:
    parser.error('ROBOLECTRIC_JAR_SIZE_MISMATCH')
h=hashlib.sha256()
with a.jar.open('rb') as stream:
    for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
if h.hexdigest()!=want:parser.error('ROBOLECTRIC_JAR_SHA256_MISMATCH')
r=Path(__file__).resolve().parent.parent
out=r/'android/app/build/synthetic-test-home/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7'/'android-all-instrumented-15-robolectric-13954326-i7.jar'
out.parent.mkdir(parents=True,exist_ok=True)
if a.jar.resolve()!=out.resolve():shutil.copy2(a.jar,out)
print('PUBLIC_ROBOLECTRIC_JAR_READY')
