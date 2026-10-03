"""Compile/test source without Gradle, APK packaging, installation or device launch."""
import argparse
import datetime
import hashlib
import json
import pathlib
import shutil
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
    parser.add_argument("--stage", default="M5")
    parser.add_argument("--report", type=pathlib.Path, default=ROOT / "verification/m5-checks.json")
    parser.add_argument("--host-json-jar", type=pathlib.Path,
                        help="Host-only org.json jar to execute LAN loopback tests; not an APK dependency")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This host check currently uses the macOS MuJoCo dylib")
    args.java_home = args.java_home.resolve()
    java = args.java_home / "bin"
    sdk = args.android_sdk.resolve()
    mujoco = args.mujoco_dir.resolve()
    if not run([java / "javac", "-version"]).startswith("javac 11"):
        parser.error("Use JDK 11 to check the unchanged Android Studio build chain")
    report = {"date": datetime.datetime.now().astimezone().isoformat(),
              "stage": args.stage + " source checks",
              "scope": "Host source checks only; no Gradle/APK/device execution"}
    with tempfile.TemporaryDirectory(prefix="microduck-source-check-") as tmp:
        out = pathlib.Path(tmp)
        with zipfile.ZipFile(args.ort_aar) as aar:
            (out / "ort.jar").write_bytes(aar.read("classes.jar"))
        sources = sorted((ROOT / "app/src/main/java/com/microduck/sim").glob("*.java"))
        sources += [ROOT / "verification" / (test + ".java") for test in
                    ("SceneFrameCheck", "RenderOptimizationCheck", "ReportDispatcherCheck", "NetworkControlCheck", "VideoFramesCheck")]
        run([java / "javac", "-Xlint:all", "-cp",
             f"{sdk / 'platforms/android-34/android.jar'}:{out / 'ort.jar'}", "-d", out, *sources])
        report["java_android34_ort120_jdk11_compile"] = "passed"
        for test in ("SceneFrameCheck", "RenderOptimizationCheck", "ReportDispatcherCheck", "VideoFramesCheck"):
            report[test] = json.loads(run([java / "java", "-cp", out, "com.microduck.sim." + test]))
        if args.host_json_jar:
            report["NetworkControlCheck"] = json.loads(run([java / "java", "-cp",
                str(out) + ":" + str(args.host_json_jar.resolve()), "com.microduck.sim.NetworkControlCheck"]))
        else:
            report["NetworkControlCheck"] = "Not executed; provide --host-json-jar for loopback tests"
        node = shutil.which("node")
        if node:
            script = (ROOT / "app/src/main/assets/controller.html").read_text().split("<script>", 1)[1].split("</script>", 1)[0]
            (out / "controller.js").write_text(script)
            run([node, "--check", out / "controller.js"])
            report["controller_javascript_syntax"] = "passed"
            report["controller_behavior"] = json.loads(run([node, ROOT / "verification/controller-check.js"]))
        else:
            report["controller_javascript_syntax"] = "Not executed; node unavailable"
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
    paths = [ROOT / "app/build.gradle", ROOT / "app/src/main/AndroidManifest.xml",
             ROOT / "app/src/main/assets/controller.html", pathlib.Path(__file__).resolve()]
    paths += sorted((ROOT / "app/src/main/cpp").glob("*"))
    paths += sources
    paths += [ROOT / "verification/visual-bridge-check.cpp", ROOT / "verification/native-contract-check.cpp"]
    paths += [ROOT / "verification/controller-check.js"]
    report["source_sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in paths if p.is_file()}
    report["baseline_device_report"] = "device-M3-realtime-render-test.json"
    report["previous_device_report"] = "device-M4-realtime-render-test.json"
    report["device_status"] = "Pending user Android Studio " + args.stage + " build, browser video and video-on realtime budget check"
    target = args.report
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
