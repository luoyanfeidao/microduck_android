"""Real upstream Rust RemoteIo -> production Java server/body -> production JNI/BAM, on host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import sys

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--mujoco-dir',type=Path,required=True)
    parser.add_argument('--host-json-jar',type=Path,required=True)
    args=parser.parse_args()
    java=args.java_home.resolve();mujoco=args.mujoco_dir.resolve();jar=args.host_json_jar.resolve()
    report={'scope':'Host Rust/Java/JNI integration, not Android APK or device verification'}
    with tempfile.TemporaryDirectory(prefix='microduck-simio-',dir='/private/tmp') as temp:
        out=Path(temp);library=mujoco/'libmujoco.3.10.0.dylib'
        native=out/'libmicroduck_m1.dylib'
        subprocess.run(['clang++','-std=c++20','-O2','-dynamiclib','-Wall','-Wextra','-Werror',
            '-I'+str(mujoco/'include'),'-I'+str(java/'include'),'-I'+str(java/'include/darwin'),
            str(ROOT/'app/src/main/cpp/native_sim.cpp'),str(library),'-o',str(native)],check=True)
        subprocess.run(['install_name_tool','-change','@rpath/mujoco.framework/Versions/A/libmujoco.3.10.0.dylib',str(library),str(native)],check=True)
        (out/'libmujoco.dylib').symlink_to(library)
        names=['NativeSim','VisualMesh','SimIoRequest','SimIoServer','SimIoBody']
        sources=[ROOT/'app/src/main/java/com/microduck/sim'/f'{n}.java' for n in names]
        sources.append(ROOT/'verification/SimIoNativeHarness.java')
        subprocess.run([str(java/'bin/javac'),'--release','11','-cp',str(jar),'-d',str(out),*map(str,sources)],check=True)
        env={**os.environ,'CARGO_TARGET_DIR':'/private/tmp/microduck-simio-cargo'}
        manifest=ROOT/'verification/rust-simio/Cargo.toml'
        subprocess.run(['cargo','build','--offline','--locked','--manifest-path',str(manifest)],env=env,check=True)
        server=subprocess.Popen([str(java/'bin/java'),'-Djava.library.path='+str(out),'-cp',str(out)+':'+str(jar),
            'com.microduck.sim.SimIoNativeHarness',str(ROOT/'app/src/main/assets/model/scene.xml')],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            port=int(server.stdout.readline().strip())
            result=subprocess.run([env['CARGO_TARGET_DIR']+'/debug/android-simio-check',f'127.0.0.1:{port}'],
                capture_output=True,text=True,check=True,timeout=20)
            report['rust_client']=json.loads(result.stdout)
            time.sleep(.15)
            # Also exercise the existing complete desktop daemon and actual policy loop.
            robotd=ROOT.parent/'Pollen/microduck/target/debug/robotd'
            if robotd.is_file():
                runtime=out/'r';helper=[sys.executable,str(ROOT/'scripts/android_robotd.py'),'--runtime-dir',str(runtime)]
                with (out/'robotd.log').open('w+') as log:
                    daemon=subprocess.Popen(helper+['run','--body',f'127.0.0.1:{port}'],stdout=log,stderr=log)
                    try:
                        for _ in range(100):
                            if daemon.poll() is not None:raise RuntimeError('robotd exited during startup')
                            if (runtime/'robotd.sock').exists():break
                            time.sleep(.05)
                        enabled=subprocess.run(helper+['enable'],capture_output=True,text=True,check=True,timeout=10)
                        time.sleep(2)
                        server.stdin.write('state\n');server.stdin.flush()
                        standing=json.loads(server.stdout.readline())
                        assert standing['running'] and standing['body_connected'] and standing['finite'],standing
                        assert standing['trunk_z']>.095,standing
                        walked=subprocess.run(helper+['walk','--seconds','1'],capture_output=True,text=True,check=True,timeout=10)
                        server.stdin.write('state\n');server.stdin.flush()
                        walking=json.loads(server.stdout.readline());assert walking['finite'] and walking['running']
                        report['complete_robotd']={'binary_sha256':hashlib.sha256(robotd.read_bytes()).hexdigest(),
                            'enable':json.loads(enabled.stdout),'standing_trunk_z':standing['trunk_z'],
                            'walk_command':walked.stdout.strip(),'walk_delta_x':walking['root_x']-standing['root_x'],
                            'finite':walking['finite']}
                    except Exception:
                        log.flush();log.seek(0);print(log.read()[-6000:],file=sys.stderr);raise
                    finally:
                        daemon.terminate()
                        try:daemon.wait(timeout=5)
                        except subprocess.TimeoutExpired:daemon.kill();daemon.wait(timeout=5)
                time.sleep(.15)
            else:report['complete_robotd']='Not run: build desktop robotd first'
            server.stdin.write('\n');server.stdin.flush()
            report['native_final_state']=json.loads(server.stdout.readline())
            server.wait(timeout=5);assert server.returncode==0
        finally:
            if server.poll() is None:server.terminate();server.wait(timeout=5)
        paths=sources+[ROOT/'app/src/main/cpp/native_sim.cpp',ROOT/'app/src/main/cpp/bam_actuator.h',Path(__file__),
            manifest,ROOT/'verification/rust-simio/src/main.rs',ROOT/'verification/rust-simio/Cargo.lock',ROOT/'scripts/android_robotd.py']
        report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
        upstream=ROOT.parent/'Pollen/microduck/duck-control/src'
        report['upstream_rust_sha256']={p:hashlib.sha256((upstream/p).read_bytes()).hexdigest() for p in ['sim.rs','io.rs','imu.rs']}
    (ROOT/'verification/m9-simio-checks.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
