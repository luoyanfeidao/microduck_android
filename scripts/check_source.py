"""Compile/test source without Gradle, APK packaging, installation or device launch."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(command):
    result = subprocess.run([str(value) for value in command], cwd=ROOT, check=True,
                            text=True, stdout=subprocess.PIPE)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=pathlib.Path, required=True)
    parser.add_argument("--android-sdk", type=pathlib.Path, required=True)
    parser.add_argument("--ort-aar", type=pathlib.Path, required=True)
    parser.add_argument("--mujoco-dir", type=pathlib.Path, required=True,
                        help="Desktop MuJoCo 3.10.0 Python package with include/ and libmujoco")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This host check currently uses the macOS MuJoCo dylib")
    java = args.java_home.resolve() / "bin"
    sdk = args.android_sdk.resolve()
    mujoco = args.mujoco_dir.resolve()
    if not run([java / "javac", "-version"]).startswith("javac 11"):
        parser.error("Use JDK 11 to check the unchanged Android Studio build chain")
    report = {"date": datetime.datetime.now().astimezone().isoformat(),
              "stage": "M3.1 indexed rendering and paced snapshot source checks",
              "scope": "Host source checks only; no Gradle/APK/device execution"}
    with tempfile.TemporaryDirectory(prefix="microduck-m31-check-") as tmp:
        out = pathlib.Path(tmp)
        with zipfile.ZipFile(args.ort_aar) as aar:
            (out / "ort.jar").write_bytes(aar.read("classes.jar"))
        sources = sorted((ROOT / "app/src/main/java/com/microduck/sim").glob("*.java"))
        sources += [ROOT / "verification/SceneFrameCheck.java", ROOT / "verification/RenderOptimizationCheck.java"]
        run([java / "javac", "-Xlint:all", "-cp",
             f"{sdk / 'platforms/android-34/android.jar'}:{out / 'ort.jar'}", "-d", out, *sources])
        report["java_android34_ort120_jdk11_compile"] = "passed"
        for test in ("SceneFrameCheck", "RenderOptimizationCheck"):
            report[test] = json.loads(run([java / "java", "-cp", out, "com.microduck.sim." + test]))
        ndk = sdk / "ndk/25.1.8937393/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++"
        run([ndk, "--target=aarch64-linux-android28", "-std=c++20", "-fsyntax-only",
             "-Wall", "-Wextra", "-Werror", "-I" + str(mujoco / "include"),
             ROOT / "app/src/main/cpp/native_sim.cpp"])
        report["android_arm64_jni_syntax_Werror"] = "passed"
        library = mujoco / "libmujoco.3.10.0.dylib"
        for name, fixture in (("visual-bridge-check", "visual-reference-fixture.txt"),
                              ("native-contract-check", "native-contract-fixture.txt")):
            exe = out / name
            run(["clang++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
                 "-I" + str(mujoco / "include"), "-I" + str(args.java_home / "include"),
                 "-I" + str(args.java_home / "include/darwin"),
                 ROOT / "verification" / (name + ".cpp"), library, "-o", exe])
            run(["install_name_tool", "-change", "@rpath/mujoco.framework/Versions/A/libmujoco.3.10.0.dylib",
                 library, exe])
            report[name] = json.loads(run([exe, ROOT / "app/src/main/assets/model/scene.xml",
                                          ROOT / "verification" / fixture]))
    paths = [ROOT / "app/build.gradle", pathlib.Path(__file__).resolve()]
    paths += sorted((ROOT / "app/src/main/cpp").glob("*"))
    paths += sources
    paths += [ROOT / "verification/visual-bridge-check.cpp", ROOT / "verification/native-contract-check.cpp"]
    report["source_sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in paths if p.is_file()}
    report["baseline_device_report"] = "device-M3-realtime-render-test.json"
    report["device_m31_status"] = "Pending user Android Studio build and 30s realtime tests with 3D on/off"
    target = ROOT / "verification/m31-checks.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
