#!/usr/bin/env python3
"""Verify the checked-in Android Studio Wrapper JAR before executing it."""
import hashlib
from project import ROOT

jar = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
expected = jar.with_suffix('.jar.sha256').read_text().strip()
if len(expected) != 64 or hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
    raise SystemExit('Gradle Wrapper JAR checksum mismatch')
print('Gradle Wrapper JAR checksum verified.')
