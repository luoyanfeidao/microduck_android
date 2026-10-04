"""Inspect pinned WebRTC AAR and D8 its dependency JARs; never builds the app or an APK."""
import argparse
import collections
import hashlib
import io
import json
import pathlib
import struct
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'android-sdk', 'builder-jar', 'webrtc-aar', 'kotlin-jar', 'annotation-jar'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--report', type=pathlib.Path, default=ROOT / 'verification/m8-video-dependencies.json')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'VIDEO_DEPENDENCIES.lock.json').read_text())
    if sha(args.webrtc_aar) != lock['webrtc']['aar_sha256']:
        raise ValueError('WebRTC AAR does not match pinned publication')
    if sha(args.kotlin_jar) != lock['transitive']['kotlin_stdlib_1.9.25_sha256']:
        raise ValueError('Kotlin JAR digest mismatch')
    if sha(args.annotation_jar) != lock['transitive']['annotation_jvm_1.9.1_sha256']:
        raise ValueError('Annotation JAR digest mismatch')
    report = {'scope': 'Dependency-only source/DEX compatibility; no Gradle, app DEX, APK or device execution',
              'webrtc_aar_sha256': sha(args.webrtc_aar)}
    java = args.java_home / 'bin/java'
    d8 = [str(java), '-cp', str(args.builder_jar), 'com.android.tools.r8.D8']
    report['d8_version'] = subprocess.check_output(d8 + ['--version'], text=True).strip()
    report['builder_sha256'] = sha(args.builder_jar)
    with zipfile.ZipFile(args.webrtc_aar) as aar, tempfile.TemporaryDirectory(prefix='microduck-video-deps-') as tmp:
        out = pathlib.Path(tmp)
        jar = aar.read('classes.jar')
        with zipfile.ZipFile(io.BytesIO(jar)) as classes:
            versions = collections.Counter(struct.unpack('>H', classes.read(name)[6:8])[0]
                                           for name in classes.namelist() if name.endswith('.class'))
        if max(versions) > 55:
            raise ValueError('WebRTC bytecode incompatible with JDK11')
        report['class_versions'] = dict(versions)
        report['aar_metadata'] = aar.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode()
        report['manifest'] = aar.read('AndroidManifest.xml').decode()
        native = aar.read('jni/arm64-v8a/libjingle_peerconnection_so.so')
        if native[:4] != b'\x7fELF' or native[4:6] != b'\x02\x01' or struct.unpack_from('<H', native, 18)[0] != 183:
            raise ValueError('Expected little-endian ELF64 AArch64')
        phoff = struct.unpack_from('<Q', native, 32)[0]
        phsize, count = struct.unpack_from('<HH', native, 54)
        alignment = [struct.unpack_from('<Q', native, phoff+i*phsize+48)[0] for i in range(count)
                     if struct.unpack_from('<I', native, phoff+i*phsize)[0] == 1]
        report['arm64_load_alignment'] = alignment
        report['arm64_jni_sha256'] = hashlib.sha256(native).hexdigest()
        report['whole_apk_16kb_supported'] = False  # ORT 1.20.0 still has 4KB load alignment.
        program = out / 'webrtc.jar'
        program.write_bytes(jar)
        result = subprocess.run(d8 + ['--min-api', '28', '--lib', str(args.android_sdk / 'platforms/android-34/android.jar'),
                                     '--output', str(out), str(program), str(args.kotlin_jar), str(args.annotation_jar)],
                                text=True, capture_output=True, check=True)
        report['dependency_dex'] = 'passed'
        report['warnings'] = result.stderr.strip()
        report['dex_files'] = [path.name for path in out.glob('*.dex')]
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key not in ('manifest', 'aar_metadata')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
