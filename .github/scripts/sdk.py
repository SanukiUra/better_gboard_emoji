"""Locate the Java and Android SDK installations provided by the CI runner."""
import os
from pathlib import Path


def java_home():
    value = os.environ.get('JAVA_HOME')
    if value and (Path(value) / 'bin/java').is_file():
        return Path(value)
    raise RuntimeError('JAVA_HOME must point to a JDK installation.')


def sdk_home():
    for key in ['ANDROID_HOME', 'ANDROID_SDK_ROOT']:
        value = os.environ.get(key)
        if value and (Path(value) / 'build-tools').is_dir():
            return Path(value)
    raise RuntimeError('ANDROID_HOME must point to an Android SDK installation.')


def version_key(path):
    return tuple(int(part) for part in path.name.split('.') if part.isdigit())
