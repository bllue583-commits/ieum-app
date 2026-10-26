from pathlib import Path
import argparse,hashlib,json,os,subprocess
r=Path(__file__).resolve().parent.parent;p=argparse.ArgumentParser();p.add_argument('--variant',choices=['debug','validation'],default='debug');p.add_argument('--label',required=True);a=p.parse_args();assert a.label.replace('-','').isalnum()
d=r/'evidence'/a.label;assert not d.exists();d.mkdir()
sdk=Path(os.environ['ANDROID_SDK_ROOT']);env=os.environ.copy();env['ADB_SERVER_SOCKET']='tcp:localhost:5044'
prefix=[str(sdk/('platform-tools/adb.exe' if os.name=='nt' else 'platform-tools/adb')),'-P','5044','-s','emulator-5594']
def adb(args,name,timeout=60):
 result=subprocess.run(prefix+args,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout);(d/name).write_text(result.stdout);return result
apk=r/f'android/app/build/outputs/apk/{a.variant}/app-{a.variant}.apk';test=r/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
for file,name in [(apk,'install-app.log'),(test,'install-test.log')]:
 x=adb(['install','-r',str(file)],name,120);assert x.returncode==0 and 'Success' in x.stdout
properties={}
for field in ['ro.product.cpu.abi','ro.build.version.sdk','ro.build.fingerprint']:
 x=adb(['shell','getprop',field],field+'.txt');assert x.returncode==0;properties[field]=x.stdout.strip()
x=adb(['shell','am','instrument','-w','-r','-e','class','kr.callbrief.AndroidWhisperDeviceTest','kr.callbrief.test/androidx.test.runner.AndroidJUnitRunner'],'instrumentation.log',300)
pull=adb(['pull','/sdcard/Android/data/kr.callbrief/files/code34-device-evidence',str(d/'public-evidence')],'pull.log',60)
ok=x.returncode==0 and 'OK (2 tests)' in x.stdout and 'FAILURES!!!' not in x.stdout
out={'status':'PASS' if ok else 'FAIL_OR_INCOMPLETE','variant':a.variant,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apk_bytes':apk.stat().st_size,'serial':'emulator-5594','adb_port':5044,'properties':properties,'instrumentation_exit':x.returncode,'public_evidence_pull_exit':pull.returncode,'realGMS':False,'realDrive':False,'privateAudio':False,'actualAndroidNativeSTT':True,'physicalArm64Phone':'NOT_RUN','x86_64Execution':'NOT_RUN'}
(d/'receipt.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out),flush=True);raise SystemExit(0 if ok else 1)
