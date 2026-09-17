#!/usr/bin/env python3
"""P11 device benchmark host-side runner.

Executes the instrumented benchmark (SravaaniBenchmarkInstrumentedTest) on every attached
Android device, pulls the JSON result, stamps the device class, and writes one file per
device under scratch/sravaani/logs/device_bench/.

Device class (master spec §17) is stamped from `adb shell getprop`:
  ro.sys.heapsize / ro.build.version.release / ro.product.model + total RAM via
  /proc/meminfo on the device. Classification:
  - low-end:  MemTotal < 3.5 GB
  - mid-range: 3.5 GB <= MemTotal < 7 GB
  - high-end: >= 7 GB (out of required matrix; still recorded)

Honesty policy (master §14/§22/§28): if no device is attached, or the instrumented test
skips (artifact-less build), the script writes a result row with status NOT_MEASURED and
the exact reason — it NEVER synthesizes numbers.
"""
import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ANDROID_TEST_CLASS = "app.swarsetu.stt.SravaaniBenchmarkInstrumentedTest"
RESULT_REMOTE = "/data/data/%s/files/sravaani_bench.json"  # debug: app external files


def sh(args: list, timeout: int = 600) -> str:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout.strip()


def adb(serial: str, *args: str, timeout: int = 600) -> str:
    return sh(["adb", "-s", serial, *args], timeout=timeout)


def attached_devices() -> list:
    out = sh(["adb", "devices"])
    devs = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def device_ram_kb(serial: str) -> int:
    out = adb(serial, "shell", "cat", "/proc/meminfo", timeout=30)
    for line in out.splitlines():
        if line.startswith("MemTotal:"):
            return int(line.split()[1])
    return -1


def classify(ram_kb: int) -> str:
    if ram_kb < 0:
        return "UNKNOWN"
    gb = ram_kb / 1024.0 / 1024.0
    if gb < 3.5:
        return "low-end"
    if gb < 7.0:
        return "mid-range"
    return "high-end"


def run_on_device(serial: str, package: str, timeout: int) -> dict:
    model = adb(serial, "shell", "getprop", "ro.product.model", timeout=30)
    release = adb(serial, "shell", "getprop", "ro.build.version.release", timeout=30)
    sdk = adb(serial, "shell", "getprop", "ro.build.version.sdk", timeout=30)
    ram_kb = device_ram_kb(serial)
    row = {
        "serial": serial,
        "model": model,
        "android_release": release,
        "sdk_int": sdk,
        "ram_kb": ram_kb,
        "device_class": classify(ram_kb),
    }

    print(f"[{serial}] installing APKs…")
    r = subprocess.run(
        ["gradlew.bat" if sys.platform == "win32" else "./gradlew",
         ":app:installDebug :app:installDebugAndroidTest".replace(" ", "")],
        capture_output=True, text=True, timeout=timeout,
    )
    # gradle can't take two tasks that way — do it properly:
    if r.returncode != 0:
        for task in (":app:installDebug", ":app:installDebugAndroidTest"):
            rc = subprocess.run(
                ["gradlew.bat" if sys.platform == "win32" else "./gradlew", task],
                capture_output=True, text=True, timeout=timeout,
            )
            if rc.returncode != 0:
                row.update(status="NOT_MEASURED", reason=f"install failed: {task}")
                return row

    print(f"[{serial}] running instrumented benchmark…")
    out = adb(serial, "shell", "am", "instrument", "-w",
              "-e", "class", ANDROID_TEST_CLASS,
              f"{package}.test/androidx.test.runner.AndroidJUnitRunner",
              timeout=timeout)
    row["instrument_output_tail"] = "\n".join(out.splitlines()[-5:])

    print(f"[{serial}] pulling result JSON…")
    blob = adb(serial, "shell", "run-as", package, "cat",
               RESULT_REMOTE % package, timeout=60)
    if not blob.startswith("{"):
        row.update(status="NOT_MEASURED",
                   reason="no benchmark JSON on device (skipped or not written)")
        return row
    try:
        data = json.loads(blob)
    except json.JSONDecodeError:
        row.update(status="NOT_MEASURED", reason="unparseable benchmark JSON")
        return row
    row.update(data)
    return row


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", default="app.swarsetu")
    ap.add_argument("--out", default="scratch/sravaani/logs/device_bench")
    ap.add_argument("--timeout", type=int, default=1800,
                    help="per-device timeout in seconds")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    devices = attached_devices()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    summary = {"timestamp_utc": stamp, "runner": "run_device_bench.py",
               "devices": []}
    if not devices:
        summary["status"] = "NOT_MEASURED"
        summary["reason"] = ("no Android device attached (adb shows none) — "
                             "physical-device matrix stays NOT MEASURED")
        print(summary["reason"])
    else:
        for serial in devices:
            print(f"=== device {serial} ===")
            row = run_on_device(serial, args.package, args.timeout)
            summary["devices"].append(row)
            (outdir / f"bench_{serial}_{stamp}.json").write_text(
                json.dumps(row, indent=2), encoding="utf-8")
            status = row.get("status", "MEASURED")
            print(f"[{serial}] status={status} class={row['device_class']} "
                  f"rtf_warm={row.get('rtf_warm_pass', 'n/a')}")

    (outdir / f"summary_{stamp}.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8")
    print(f"summary -> {outdir / ('summary_' + stamp + '.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
