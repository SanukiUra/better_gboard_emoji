#!/usr/bin/env python3
"""Prepare a verified signed APK and SHA256SUMS for GitHub Releases."""
import argparse
from pathlib import Path
import shutil
from project import ROOT, read_version
from verify_apk import verify_apk

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--tag', required=True)
parser.add_argument('--output', type=Path, default=ROOT / 'dist')
args = parser.parse_args()
name, _ = read_version(args.tag)
report = verify_apk(args.apk, tag=args.tag, release=True)
args.output.mkdir(parents=True, exist_ok=True)
filename = 'Gboard-Emoji-Input-Enhancer-' + name + '.apk'
shutil.copyfile(args.apk, args.output / filename)
(args.output / 'SHA256SUMS').write_text(report['sha256'] + '  ' + filename + '\n', encoding='utf-8')
print('Release assets prepared in ' + str(args.output))
