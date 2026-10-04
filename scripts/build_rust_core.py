"""Build the embedded Android static library, invoked by CMake (no APK operations)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
def verify_sources():
    lock=json.loads((ROOT/'RUST_CORE.lock.json').read_text())
    for name,digest in lock['upstream_sha256'].items():
        path=ROOT.parent/'Pollen/microduck'/name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
            raise RuntimeError('Rust upstream source changed; review/revalidate RUST_CORE.lock.json: '+name)
    return lock

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cargo',default=str(Path.home()/'.cargo/bin/cargo'))
    p.add_argument('--target-dir',type=Path,required=True)
    p.add_argument('--linker',type=Path,required=True)
    p.add_argument('--offline',action='store_true')
    args=p.parse_args();verify_sources()
    if not args.linker.is_file():p.error('Android NDK clang linker missing: '+str(args.linker))
    env={**os.environ,'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER':str(args.linker),
        'CARGO_TARGET_DIR':str(args.target_dir.resolve())}
    command=[args.cargo,'build','--locked','--release','--target','aarch64-linux-android',
        '--manifest-path',str(ROOT/'rust/control-core/Cargo.toml')]
    if args.offline:command.append('--offline')
    subprocess.run(command,env=env,check=True)
if __name__=='__main__':main()
