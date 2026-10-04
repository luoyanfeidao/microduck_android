"""Host execution of production Android PolicyRunner -> Rust -> ORT -> JNI/MuJoCo."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from build_rust_core import verify_sources
ROOT=Path(__file__).resolve().parents[1]
def run(command,**kwargs):
    return subprocess.run(list(map(str,command)),check=True,text=True,**kwargs)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home',type=Path,required=True)
    p.add_argument('--mujoco-dir',type=Path,required=True)
    p.add_argument('--ort-jar',type=Path,required=True)
    p.add_argument('--host-json-jar',type=Path,required=True)
    args=p.parse_args();verify_sources()
    java=args.java_home.resolve();mj=args.mujoco_dir.resolve();ort=args.ort_jar.resolve();jsonjar=args.host_json_jar.resolve()
    env={**os.environ,'CARGO_TARGET_DIR':'/private/tmp/microduck-embedded-rust-host'}
    manifest=ROOT/'rust/control-core/Cargo.toml'
    tests=run(['cargo','test','--offline','--locked','--manifest-path',manifest,'--test','core'],env=env,capture_output=True)
    run(['cargo','build','--offline','--locked','--release','--manifest-path',manifest],env=env)
    with tempfile.TemporaryDirectory(prefix='duck-embedded-') as temp:
        out=Path(temp);lib=mj/'libmujoco.3.10.0.dylib';native=out/'libmicroduck_m1.dylib'
        run(['clang++','-std=c++20','-O2','-dynamiclib','-Wall','-Wextra','-Werror',
            '-I'+str(mj/'include'),'-I'+str(java/'include'),'-I'+str(java/'include/darwin'),
            ROOT/'app/src/main/cpp/native_sim.cpp',ROOT/'app/src/main/cpp/rust_control_jni.cpp',
            Path(env['CARGO_TARGET_DIR'])/'release/libmicroduck_android_core.a',lib,'-o',native])
        run(['install_name_tool','-change','@rpath/mujoco.framework/Versions/A/libmujoco.3.10.0.dylib',lib,native])
        (out/'libmujoco.dylib').symlink_to(lib)
        # Only Android asset access is replaced; PolicyRunner and inference are production code.
        (out/'Context.java').write_text('package android.content; public class Context { private final android.content.res.AssetManager assets; public Context(String root){assets=new android.content.res.AssetManager(root);} public android.content.res.AssetManager getAssets(){return assets;} }')
        (out/'AssetManager.java').write_text('package android.content.res; public class AssetManager { private final String root; public AssetManager(String root){this.root=root;} public java.io.InputStream open(String path)throws java.io.IOException{return new java.io.FileInputStream(new java.io.File(root,path));} }')
        names=['NativeSim','VisualMesh','RustControl','PolicyRunner','RouladeWindow','CapturedReport']
        sources=[ROOT/'app/src/main/java/com/microduck/sim'/f'{n}.java' for n in names]+[ROOT/'verification/EmbeddedRustCheck.java']
        run([java/'bin/javac','--release','11','-cp',str(ort)+':'+str(jsonjar),'-d',out,*sources,out/'Context.java',out/'AssetManager.java'])
        result=run([java/'bin/java','-Djava.library.path='+str(out),'-cp',str(out)+':'+str(ort)+':'+str(jsonjar),
            'com.microduck.sim.EmbeddedRustCheck',ROOT/'app/src/main/assets/model/scene.xml',ROOT/'app/src/main/assets'],capture_output=True)
        report={'scope':'Host production Java/JNI/Rust/ORT closed loop, not Android device validation',
            'rust_tests':tests.stdout,'closed_loop':json.loads(result.stdout),
            'desktop_ort_jar_sha256':hashlib.sha256(ort.read_bytes()).hexdigest()}
        sources += list((ROOT/'rust/control-core/src').glob('*.rs'))+list((ROOT/'rust/control-core/tests').glob('*.rs'))
        sources += [ROOT/'rust/control-core/build.rs',manifest,ROOT/'rust/control-core/Cargo.lock',ROOT/'scripts/build_rust_core.py',Path(__file__),
            ROOT/'app/src/main/cpp/native_sim.cpp',ROOT/'app/src/main/cpp/bam_actuator.h',ROOT/'app/src/main/cpp/rust_control_jni.cpp',ROOT/'app/src/main/cpp/rust_control.h',ROOT/'RUST_CORE.lock.json']
        report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    (ROOT/'verification/m10-embedded-rust-checks.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
