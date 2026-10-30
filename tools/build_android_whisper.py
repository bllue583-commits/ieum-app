"""Pinned public source, Android CPU JNI, no service credentials or SDK mutation."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tarfile
r=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--abis',default='arm64-v8a,x86_64');a=p.parse_args()
abis=a.abis.split(',');assert set(abis)<=set(['arm64-v8a','x86_64'])
t=r.parent/'.code34-native-tools';ndk=Path(os.environ.get('IEUM_ANDROID_NDK_ROOT',str(t/'android-ndk-r28c')))
cmake=Path(os.environ.get('IEUM_CMAKE_BINARY',shutil.which('cmake') or 'cmake'))
assert '28.2.13676358' in (ndk/'source.properties').read_text()
archive=r/'third_party/whisper.cpp-v1.7.6.tar.gz';source_sha=hashlib.sha256(archive.read_bytes()).hexdigest();assert source_sha=='166140e9a6d8a36f787a2bd77f8f44dd64874f12dd8359ff7c1f4f9acb86202e'
work=r/'android/app/build/android-whisper';work.mkdir(parents=True,exist_ok=True)
source=work/'source/whisper.cpp-1.7.6'
if not source.exists():
 with tarfile.open(archive) as tar:
  assert all(not x.name.startswith('/') and '..' not in Path(x.name).parts and not x.issym() and not x.islnk() for x in tar.getmembers());tar.extractall(work/'source')
out=r/'android/app/build/generated/ieum-whisper';assets=out/'assets/local-stt';assets.mkdir(parents=True,exist_ok=True)
pins={}
for abi in abis:
 build=work/abi;log=work/(abi+'.log')
 make=os.environ.get('IEUM_MAKE_BINARY',shutil.which('make') or shutil.which('mingw32-make') or '')
 if not make:raise RuntimeError('Set IEUM_MAKE_BINARY to a prepared make executable')
 cmd=[str(cmake),'-S',str(r/'android/app/src/main/cpp'),'-B',str(build),'-G','Unix Makefiles','-DCMAKE_MAKE_PROGRAM='+make,'-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON','-DCMAKE_BUILD_TYPE=Release','-DWHISPER_SOURCE='+str(source)]
 with log.open('w') as f:
  subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,check=True)
  subprocess.run([str(cmake),'--build',str(build),'--target','ieum_whisper','-j','4'],stdout=f,stderr=subprocess.STDOUT,check=True)
 lib=build/'libieum_whisper.so';dest=out/'jniLibs'/abi/lib.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(lib,dest);pins[abi]={'sha256':hashlib.sha256(lib.read_bytes()).hexdigest(),'bytes':lib.stat().st_size}
 print(json.dumps({'abi':abi,**pins[abi]}),flush=True)
for name in ['public-ko.wav','public-en.wav']:shutil.copy2(r/'worker/tests/audio'/name,assets/name)
meta={'schema':'ieum.android-whisper-runtime.v1','engine':'whisper.cpp-v1.7.6-android-cpu','sourceArchiveSha256':source_sha,'native':pins,'publicAudio':{n:hashlib.sha256((assets/n).read_bytes()).hexdigest() for n in ['public-ko.wav','public-en.wav']},'network':False,'microphone':False,'ndk':'28.2.13676358'}
(assets/'runtime-pin.json').write_text(json.dumps(meta,indent=2)+'\n');(r/'evidence').mkdir(exist_ok=True);(r/'evidence/ANDROID_NATIVE_BUILD_RECEIPT.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps({'status':'ANDROID_NATIVE_ASSETS_READY','abis':abis}),flush=True)
