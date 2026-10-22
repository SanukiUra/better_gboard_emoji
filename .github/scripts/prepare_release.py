#!/usr/bin/env python3
"""Validate a release tag and restore the signing keystore from CI environment variables."""
import argparse
import base64
import os
from pathlib import Path
from project import read_version

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tag', required=True)
args = parser.parse_args()
name, code = read_version(args.tag)
keys = ['ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']
missing = [key for key in keys if not os.environ.get(key)]
if missing:
    raise SystemExit('Configure these GitHub Actions repository secrets before publishing: ' + ', '.join(missing))
directory = Path(os.environ['RUNNER_TEMP']) / 'gboard-release-signing'
directory.mkdir(mode=0o700, parents=True, exist_ok=True)
path = directory / 'release.keystore'
encoded = ''.join(os.environ['ANDROID_KEYSTORE_BASE64'].split())
data = base64.b64decode(encoded, validate=True)
if not data:
    raise SystemExit('ANDROID_KEYSTORE_BASE64 contains an empty keystore')
with path.open('wb') as output:
    path.chmod(0o600)
    output.write(data)
with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
    output.write('ANDROID_KEYSTORE_PATH=' + str(path) + '\n')
print('Release ' + args.tag + ' validated (versionCode ' + str(code) + '); signing keystore restored.')
