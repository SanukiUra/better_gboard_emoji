#!/usr/bin/env python3
"""Verify APK version, signature, alignment, LSPosed metadata and packaged DEX classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from sdk import java_home, sdk_home, version_key
from dex import Dex
from project import read_version


def verify_apk(path, tag=None, release=False):
    path = Path(path).resolve()
    name, code = read_version(tag)
    sdk_tools = sorted((sdk_home() / 'build-tools').iterdir(), key=version_key)[-1]
    env = dict(os.environ, JAVA_HOME=str(java_home()))
    subprocess.run([str(sdk_tools / 'apksigner'), 'verify', '--verbose', str(path)], check=True, env=env)
    subprocess.run([str(sdk_tools / 'zipalign'), '-c', '-p', '4', str(path)], check=True)
    badging = subprocess.check_output([str(sdk_tools / 'aapt'), 'dump', 'badging', str(path)], text=True)
    version = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if version is None or version.groups() != ('io.github.sanuki.gboardemoji', str(code), name):
        raise ValueError('Unexpected APK application ID or version')
    if release and 'application-debuggable' in badging:
        raise ValueError('A debuggable APK cannot be published as a Release')
    manifest = subprocess.check_output([str(sdk_tools / 'aapt'), 'dump', 'xmltree', str(path), 'AndroidManifest.xml'], text=True)
    for key in ['xposedmodule', 'xposeddescription', 'xposedminversion', 'xposedscope']:
        if '="' + key + '"' not in manifest:
            raise ValueError('Missing LSPosed metadata: ' + key)
    with zipfile.ZipFile(path) as apk:
        if apk.read('assets/xposed_init').strip() != b'io.github.sanuki.gboardemoji.EmojiHook':
            raise ValueError('Unexpected Xposed entry point')
        source = json.loads(apk.read('assets/mapping-source.json'))
        if hashlib.sha256(apk.read('assets/emoji-map.tsv')).hexdigest() != source['source_sha256']:
            raise ValueError('Emoji mapping does not match its source checksum')
        if 'assets/hooks/176027078.properties' not in apk.namelist():
            raise ValueError('Missing the verified Gboard 18.4 profile')
        classes = {kind for entry in apk.namelist() if entry.endswith('.dex')
                   for kind in Dex(apk.read(entry)).class_names()}
        if 'Lio/github/sanuki/gboardemoji/EmojiHook;' not in classes:
            raise ValueError('Module entry class missing from DEX')
        if any(kind.startswith(('Lde/robv/android/xposed/', 'Lorg/junit/')) for kind in classes):
            raise ValueError('Compile-only Xposed or test classes were packaged into the APK')
    report = {'application_id': version.group(1), 'version_name': name, 'version_code': code,
              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    print(json.dumps(report, indent=2))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--tag')
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()
    verify_apk(args.apk, args.tag, args.release)
