"""Shared version metadata for the offline builder and GitHub release scripts."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read_version(tag=None):
    values = {}
    for line in (ROOT / 'version.properties').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key.strip() in values:
            raise ValueError('Duplicate version property: ' + key)
        values[key.strip()] = value.strip()
    name, code = values['VERSION_NAME'], int(values['VERSION_CODE'])
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', name) or code < 1:
        raise ValueError('Expected a semantic VERSION_NAME and a positive VERSION_CODE')
    if tag is not None and tag != 'v' + name:
        raise ValueError('Tag ' + tag + ' does not match version.properties: v' + name)
    return name, code
