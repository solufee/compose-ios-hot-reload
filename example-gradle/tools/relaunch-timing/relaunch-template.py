#!/usr/bin/env python3
"""#60 stage 1: relaunch steps of plugin warm restarts on a Gradle project (measurement only).
   relaunch-template.py --project DIR --lib-file REL --app-file REL --out DIR [--n 3]
Each edit adds a top-level property (WARM top-level-property). Gradle runs with --no-daemon so the plugin's xcrun calls
go through the timing shim; the plugin's launch log (shared/launch.log) is stamped line by line."""
import argparse, os, subprocess, sys, time
ap = argparse.ArgumentParser()
ap.add_argument("--project", required=True); ap.add_argument("--lib-file", required=True); ap.add_argument("--app-file", required=True)
ap.add_argument("--out", required=True); ap.add_argument("--n", type=int, default=3); ap.add_argument("--log", default="shared/launch.log"); ap.add_argument("--bundle", default="org.example.kmphr.hotreload"); ap.add_argument("--udid", default="0B879832-98D9-49A5-AC00-8BAB579AFBB2")
a = ap.parse_args()
P = os.path.abspath(a.project); OUT = os.path.abspath(a.out); os.makedirs(OUT, exist_ok=True)
M = os.path.dirname(os.path.abspath(__file__)); LOG = os.path.join(P, a.log)
def gradle(*args, env=None, out=None):
    cmd = [os.path.join(P, "gradlew"), "-p", P, *args, "--console=plain"]
    pr = subprocess.Popen(cmd, cwd=P, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
    lines = []
    for line in pr.stdout:
        lines.append(f"{time.time():.3f}\t{line}")
    pr.wait()
    if out:
        open(out, "w").writelines(lines)
    return pr.returncode, "".join(l.split("\t", 1)[1] for l in lines)
files = {"ui": os.path.join(P, a.lib_file), "app": os.path.join(P, a.app_file)}
orig = {k: open(f).read() for k, f in files.items()}
try:
    rc, out = gradle("hotReloadPackage"); print("package rc", rc, flush=True)
    rc2, out2 = gradle("hotReloadRun"); print("run rc", rc2, flush=True)
    if rc or rc2:
        print(out[-3000:] + out2[-3000:]); sys.exit(1)
    time.sleep(3)
    for kind in ("ui", "app"):
        for i in range(1, a.n + 1):
            with open(files[kind], "a") as f:
                f.write(f"\nval relaunchProbe{kind.capitalize()}{i}: Int = {i}\n")
            base = os.path.join(OUT, f"{kind}{i}")
            for suffix in ("-shim.log",):
                if os.path.exists(base + suffix): os.remove(base + suffix)
            stop = os.path.join(OUT, "stop")
            if os.path.exists(stop): os.remove(stop)
            w = subprocess.Popen([sys.executable, os.path.join(M, "watch-log.py"), LOG, base + "-timeline.txt", stop])
            time.sleep(0.2)
            env = dict(os.environ, PATH=os.path.join(M, "shim") + os.pathsep + os.environ["PATH"], RELAUNCH_TIMING_LOG=base + "-shim.log")
            rc, out = gradle("hotReload", "--no-daemon", env=env, out=base + "-reload.out")
            time.sleep(1); open(stop, "w").close(); w.wait()
            line = subprocess.run([sys.executable, os.path.join(M, "parse60.py"), base], capture_output=True, text=True).stdout.strip()
            print(("OK " if rc == 0 and "WARM OK" in out else "FAIL ") + line, flush=True)
            open(os.path.join(OUT, "steps.txt"), "a").write(line + "\n")
finally:
    for k, f in files.items():
        open(f, "w").write(orig[k])
    subprocess.run(["xcrun", "simctl", "terminate", a.udid, a.bundle], capture_output=True)
    gradle("hotReloadDaemonStop")
print(subprocess.run([sys.executable, os.path.join(M, "parse60.py"), "--summary", os.path.join(OUT, "steps.txt")], capture_output=True, text=True).stdout)
