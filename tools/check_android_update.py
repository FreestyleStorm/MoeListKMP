"""Disposable emulator test: install-in-place, private data, Settings updater."""
import hashlib
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

root = pathlib.Path('update-proof')
evidence = root / 'evidence'
evidence.mkdir(parents=True, exist_ok=True)
package = 'com.axiel7.moelist.debug'
original_code = int(re.search(r'^code\s*=\s*(\d+)', pathlib.Path('version.properties').read_text(), re.M)[1])
next_code = original_code + 1
def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT).strip()
def screenshot(name):
    data = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'])
    (evidence / name).write_bytes(data)
def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/update-ui.xml')
    text = adb('shell', 'cat', '/sdcard/update-ui.xml')
    (evidence / 'settings-ui.xml').write_text(text)
    return ET.fromstring(text)
def click(text):
    for attempt in range(8):
        for node in tree().iter('node'):
            if node.get('text') == text or node.get('content-desc') == text:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(2)
                return
        time.sleep(1)
    screenshot('failure.png')
    raise AssertionError(f'Could not find {text!r}')

adb('root')
adb('wait-for-device')
adb('install', '-r', str(root / 'before.apk'))
adb('shell', 'am', 'start', '-W', '-n', f'{package}/com.axiel7.moelist.MainActivity')
time.sleep(8)
assert adb('shell', 'pidof', package), 'Baseline app failed to launch'
adb('shell', 'am', 'force-stop', package)
private = f'/data/user/0/{package}'
sentinel = root / 'data-sentinel.json'
sentinel.write_text(json.dumps({'fake_login': 'test-only-token', 'saved_list': [79, 80], 'theme': 'dark'}))
expected = hashlib.sha256(sentinel.read_bytes()).hexdigest()
adb('shell', 'mkdir', '-p', f'{private}/files')
adb('push', str(sentinel), f'{private}/files/update-sentinel.json')
adb('install', '-r', str(root / 'after.apk'))
retained = subprocess.check_output(['adb', 'exec-out', 'cat', f'{private}/files/update-sentinel.json'])
assert hashlib.sha256(retained).hexdigest() == expected, 'Private app data changed during update'
assert f'versionCode={next_code}' in adb('shell', 'dumpsys', 'package', package), 'New version was not installed'
adb('logcat', '-c')
adb('shell', 'am', 'start', '-W', '-n', f'{package}/com.axiel7.moelist.MainActivity')
time.sleep(8)
assert adb('shell', 'pidof', package), 'Updated app failed to launch'
click('More')
click('Settings')
assert any(node.get('text') == 'App updates' for node in tree().iter('node'))
screenshot('app-updates-settings.png')
click('Check for updates')
for attempt in range(30):
    texts = [node.get('text', '') for node in tree().iter('node')]
    if 'You’re up to date.' in texts:
        break
    assert not any('Could not check' in text for text in texts), texts
    time.sleep(1)
else:
    raise AssertionError('Update check did not complete')
screenshot('app-updates-checked.png')
(evidence / 'result.json').write_text(json.dumps({
    'installed_before': original_code, 'installed_after': next_code,
    'private_data_sha256': expected, 'private_data_preserved': True,
    'updated_app_launch': True, 'settings_updater_visible': True,
    'github_update_check': True, 'uses_real_mal_credentials': False,
    'signing': 'Disposable emulator fixture certificate; production APK signing is verified separately.'
}, indent=2))
print('Android update retained private data; Settings updater and GitHub check passed.')
