"""Inspect the actual NDK-linked library containing embedded Rust; no APK/device operations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from build_rust_core import verify_sources
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ndk-bin',type=Path,required=True)
    p.add_argument('--library',type=Path,required=True)
    args=p.parse_args();verify_sources()
    lib=args.library.resolve();tool=args.ndk_bin/'llvm-readelf'
    def read(*flags):return subprocess.check_output([str(tool),*flags,str(lib)],text=True)
    header=read('-h');program=read('-l');dynamic=read('-d');symbols=read('--dyn-syms','--wide')
    assert 'AArch64' in header and 'DYN (Shared object file)' in header
    align=[int(line.split()[-1],16) for line in program.splitlines() if line.strip().startswith('LOAD ')]
    assert align and all(value>=16384 for value in align)
    needed=re.findall(r'Shared library: \[(.*?)\]',dynamic)
    assert set(needed)=={'libmujoco.so','liblog.so','libdl.so','libm.so','libc.so'},needed
    exports=['Java_com_microduck_sim_RustControl_'+n for n in ['open','close','command','tick']]
    exports += ['duck_core_'+n for n in ['open','close','command','tick']]
    for name in exports:
        assert any(line.split()[-1]==name and ' UND ' not in line for line in symbols.splitlines() if line.split()),name
    paths=[ROOT/'app/src/main/cpp/CMakeLists.txt',ROOT/'app/src/main/cpp/native_sim.cpp',ROOT/'app/src/main/cpp/bam_actuator.h',
        ROOT/'app/src/main/cpp/rust_control_jni.cpp',ROOT/'app/src/main/cpp/rust_control.h',ROOT/'scripts/build_rust_core.py',Path(__file__),ROOT/'RUST_CORE.lock.json',
        ROOT/'rust/control-core/Cargo.toml',ROOT/'rust/control-core/Cargo.lock',ROOT/'rust/control-core/build.rs']
    paths += list((ROOT/'rust/control-core/src').glob('*.rs'))
    report={'scope':'NDK/CMake ARM64 shared library successfully linked; no Gradle/APK/device run',
        'machine':'AArch64','load_alignment':align,'needed':needed,'defined_exports':exports,
        'library_sha256':hashlib.sha256(lib.read_bytes()).hexdigest(),
        'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
    (ROOT/'verification/m10-android-rust-checks.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
