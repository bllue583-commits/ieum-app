from pathlib import Path
import json,os,shutil,subprocess
r=Path(__file__).resolve().parent.parent;sdk=Path(os.environ['ANDROID_SDK_ROOT'])
user=r/'runtime/android-user';user.mkdir(parents=True,exist_ok=True)
existing=Path(os.environ['IEUM_EXISTING_ANDROID_USER_HOME'])
for name in ['adbkey','adbkey.pub']:
 if not (user/name).exists():shutil.copy2(existing/name,user/name)
env=os.environ.copy();env.update(ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk),ANDROID_USER_HOME=str(user),ANDROID_EMULATOR_HOME=str(user),ANDROID_AVD_HOME=str(r/'runtime/avd'),ANDROID_ADB_SERVER_PORT='5044',ADB_SERVER_SOCKET='tcp:localhost:5044',ADB_VENDOR_KEYS=str(existing))
subprocess.run([str(sdk/'platform-tools/adb'),'-P','5044','start-server'],env=env,check=True)
cmd=[str(sdk/'emulator/emulator'),'-avd','ieum_code34_public35_arm64','-sysdir',str(sdk/'system-images/android-35/default/arm64-v8a'),'-port','5594','-read-only','-no-window','-no-snapshot','-no-boot-anim','-no-audio','-gpu','swiftshader','-memory','2048','-cores','2','-no-metrics','-crash-report-mode','never']
with (r/'evidence/ANDROID_EMULATOR_STARTUP.log').open('w') as out:
 p=subprocess.Popen(cmd,env=env,stdout=out,stderr=subprocess.STDOUT);(r/'evidence/ANDROID_EMULATOR_PROCESS.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'adb_port':5044,'serial':'emulator-5594','original_AVD_untouched':True},indent=2)+'\n');print('Private public-only Android AVD PID',p.pid,flush=True);raise SystemExit(p.wait())
