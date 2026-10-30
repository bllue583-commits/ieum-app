# Local design preview: capture every screen of the preview build (IEUM_LOCAL_PREVIEW=1) on the running emulator.
# Usage: powershell -File tools/preview_shots.ps1 <prefix>   -> evidence/shots/<prefix>_*.png
param([string]$prefix="shot")
$adb="$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"; if(-not (Test-Path $adb)){ $adb="C:\Users\bllue\Android\Sdk\platform-tools\adb.exe" }
$out=Join-Path $PSScriptRoot "..\evidence\shots"; New-Item -ItemType Directory -Force $out | Out-Null
function cap($name){ Start-Sleep 3; cmd /c "`"$adb`" exec-out screencap -p > `"$out\${prefix}_$name.png`"" }
function home { & $adb shell am force-stop kr.callbrief; & $adb shell monkey -p kr.callbrief -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; Start-Sleep 4 }
# Finds a view by its text in a uiautomator dump and taps its centre.
function tap($text){
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; $xml=(& $adb exec-out cat /sdcard/ui.xml) -join ""
    $m=[regex]::Match($xml,"text=`"$([regex]::Escape($text))`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if(-not $m.Success){ Write-Host "not found: $text"; return }
    $x=([int]$m.Groups[1].Value+[int]$m.Groups[3].Value)/2; $y=([int]$m.Groups[2].Value+[int]$m.Groups[4].Value)/2
    & $adb shell input tap $x $y
}
function scroll { & $adb shell input swipe 540 1800 540 600 400 }
# first launch: let the preview data finish before anything is stopped
& $adb shell monkey -p kr.callbrief -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; Start-Sleep 12; & $adb shell am force-stop kr.callbrief; & $adb shell monkey -p kr.callbrief -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null; foreach($ms in 1500,2300){ Start-Sleep -Milliseconds 700; cmd /c "`"$adb`" exec-out screencap -p > `"$out\${prefix}_0_intro_$ms.png`"" }
home; cap "1_home"; scroll; cap "1_home_b"
home; tap "통화"; cap "2_today"; scroll; cap "2_today_b"
home; tap "체크"; cap "3_tasks"; scroll; cap "3_tasks_b"
home; tap "업체"; cap "5_companies"; tap "△△철강"; Start-Sleep 3; cap "6_company_detail"; scroll; cap "6_company_detail_b"
home; tap "설정"; cap "4_settings"; scroll; cap "4_settings_b"
Get-ChildItem $out -Filter "${prefix}_*.png" | ForEach-Object { $_.Name }
