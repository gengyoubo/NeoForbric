"""Serial, isolated mod regression runner. Requires only Python 3.10+ stdlib."""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

from benchmark_process import ProcessTree

STAGES = ("admission", "menu", "world")
FIELDS = ("case_id", "stage", "profile", "status", "exit_code", "duration_ms",
          "startup_ms", "timeout_s", "audit_outcome", "failure_code", "reason", "run_dir")


def stage_list(value):
    stages = tuple(part.strip() for part in re.split("[,;]", value) if part.strip())
    if not stages or tuple(stage for stage in STAGES if stage in stages) != stages:
        raise ValueError("Stages must be admission,menu,world in that order (subsets allowed)")
    return stages


def timeout_value(value):
    timeout = float(value)
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError("Timeout must be a positive finite number")
    return timeout


def paths(value, base):
    return [(base / part.strip()).resolve() for part in value.split(";") if part.strip()]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def infer_profile(inputs):
    descriptors = []
    for item in inputs:
        with zipfile.ZipFile(item["path"]) as jar:
            names = set(jar.namelist())
        forge = "META-INF/mods.toml" in names
        neo = "META-INF/neoforge.mods.toml" in names
        other = "fabric.mod.json" in names or "neoforbric.mod.json" in names
        if not (forge or neo or other):
            raise ValueError(f"No supported mod descriptor in {item['path']}")
        descriptors.append((forge, neo, other))
    exclusive_forge = any(f and not n and not o for f, n, o in descriptors)
    exclusive_neo = any(n and not f and not o for f, n, o in descriptors)
    if exclusive_neo or (not exclusive_forge and any(n and not o for f, n, o in descriptors)):
        return "neoforge"
    return "forge" if exclusive_forge else "fabric"


def read_cases(path, stages, timeout):
    cases, identifiers, hashes = [], set(), {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not {"case_id", "mods"}.issubset(reader.fieldnames):
            raise ValueError("CSV requires case_id and mods columns")
        for row in reader:
            if not any(row.values()):
                continue
            if None in row or any(value is None for value in row.values()):
                raise ValueError(f"CSV row {reader.line_num}: column count differs from header")
            enabled = (row.get("enabled") or "true").strip().lower()
            if enabled not in ("true", "false", "1", "0"):
                raise ValueError(f"Invalid enabled value: {enabled}")
            if enabled in ("false", "0"):
                continue
            identifier = row["case_id"].strip()
            if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}", identifier) or identifier in identifiers:
                raise ValueError(f"Invalid or duplicate case_id: {identifier}")
            identifiers.add(identifier)
            inputs, names = [], set()
            for role, column in (("target", "mods"), ("dependency", "dependencies")):
                for jar in paths(row.get(column) or "", path.parent):
                    if not jar.is_file() or jar.suffix.lower() != ".jar":
                        raise ValueError(f"{identifier}: Missing input JAR: {jar}")
                    if jar.name.casefold() in names:
                        raise ValueError(f"{identifier}: Duplicate input filename: {jar.name}")
                    names.add(jar.name.casefold())
                    if jar not in hashes:
                        hashes[jar] = sha256(jar)
                    inputs.append({"path": str(jar), "role": role, "sha256": hashes[jar]})
            inferred = infer_profile(inputs)  # Also checks every JAR/descriptor for explicit profiles.
            profile = (row.get("profile") or "auto").strip()
            if profile == "auto":
                profile = inferred
            if profile not in ("fabric", "neoforge", "forge"):
                raise ValueError(f"{identifier}: Unknown profile: {profile}")
            cases.append({"case_id": identifier, "inputs": inputs, "profile": profile,
                          "stages": stage_list(row["stages"]) if row.get("stages") else stages,
                          "timeout_s": timeout_value(row.get("timeout_s") or timeout)})
    if not cases:
        raise ValueError("No enabled cases")
    return cases


def atomic_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    temporary.replace(path)


def load_runtime(path, cases):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported runtime manifest")
    required = [manifest["java"], manifest["runtime"], manifest["client_ui"], *manifest["classpath"]]
    for profile in {case["profile"] for case in cases}:
        if profile not in manifest["profiles"]:
            raise ValueError(f"Runtime was not prepared for {profile}; set -PbenchmarkProfiles")
        required.extend(manifest["profiles"][profile].values())
    for file in required:
        if not Path(file).is_absolute() or not Path(file).is_file():
            raise ValueError(f"Missing/relative runtime file: {file}; rerun prepareBenchmarkRuntime")
    if not Path(manifest["assets"]).is_dir():
        raise ValueError("Missing client assets directory")
    manifest["file_sha256"] = {file: sha256(Path(file)) for file in set(required)}
    return manifest


def command_for(manifest, case, stage, run, heap, frames):
    command = [manifest["java"], f"-Xmx{heap}", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
               "-Dstderr.encoding=UTF-8", "-Dneoforbric.debug=true",
               f"-Dneoforbric.benchmark.stage={stage}", f"-Dneoforbric.testCase={case['case_id']}"]
    profile = case["profile"]
    if profile != "fabric":
        native = manifest["profiles"][profile]
        command += [f"-Dneoforbric.{profile}.runtime={native['runtime']}",
                    f"-Dneoforbric.{profile}.bridge={native['bridge']}",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.jar=ALL-UNNAMED",
                    "--add-exports=java.base/sun.security.util=ALL-UNNAMED"]
        if profile == "neoforge":
            command.append(f"-Dneoforbric.forge.compat.root={manifest['forge_compat_root']}")
    command += ["-cp", os.pathsep.join(manifest["classpath"]), "org.neoforbric.bootstrap.Main",
                "--minecraft-client", "--runtime", manifest["runtime"], "--mods", str(run / "mods"),
                "--client-ui", manifest["client_ui"], "--audit", str(run / "audit.json")]
    if stage != "admission":
        command += ["--verify", "org.neoforbric.client.BenchmarkProbe"]
    if stage == "menu":
        command += ["--stop-after-frames", str(frames)]
    command += ["--", "--username", "NFBenchmark", "--uuid", str(uuid.uuid3(uuid.NAMESPACE_DNS, "NFBenchmark")),
                "--accessToken", "0", "--userType", "legacy", "--version", "1.21.1",
                "--versionType", "release", "--gameDir", str(run), "--assetsDir", manifest["assets"],
                "--assetIndex", "17", "--width", "960", "--height", "540"]
    return command


def resolve_dependencies(manifest, cases, pools, work):
    """One passive metadata JVM for the whole batch; no game or mod class loading."""
    work.mkdir(parents=True, exist_ok=True)
    pool = sorted({str(jar.resolve()) for directory in pools if directory.is_dir()
                   for jar in directory.rglob("*.jar") if jar.is_file()})
    atomic_json(work / "request.json", {"schema_version": 1, "pool": pool, "cases": cases})
    command = [manifest["java"], "-Xmx2g", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(manifest["classpath"]),
               "org.neoforbric.loader.BenchmarkDependencies", str(work / "request.json"), str(work / "resolved.json")]
    with (work / "console.log").open("wb") as output:
        tree = ProcessTree(command, work, output)
        try:
            code = tree.process.wait(timeout=600)
        finally:
            tree.close()
    if code != 0:
        raise ValueError(f"Dependency planner failed; see {work / 'console.log'}")
    plan = json.loads((work / "resolved.json").read_text(encoding="utf-8"))
    indexed = {item["case_id"]: item for item in plan["cases"]}
    hashes = {}
    for case in cases:
        resolved = indexed[case["case_id"]]
        case["dependency_result"] = resolved
        if resolved["status"] != "READY":
            continue
        original = {item["path"]: item for item in case["inputs"]}
        inputs = []
        for item in resolved["inputs"]:
            path = item["path"]
            if path in original:
                value = dict(original[path])
            else:
                if path not in hashes:
                    hashes[path] = sha256(Path(path))
                value = {"path": path, "role": "dependency", "sha256": hashes[path]}
            value["declared_mods"] = item["mods"]
            value["required"] = item["required"]
            inputs.append(value)
        case["inputs"] = inputs
    return cases


def audit_for(run):
    # A reader can lock audit.json on Windows; NF saves a complete fallback.
    candidates = [run / "audit.json", *run.glob("audit.json.fallback-*.json")]
    errors = []
    for path in sorted((p for p in candidates if p.exists()), key=lambda p: p.stat().st_mtime_ns, reverse=True):
        try:
            audit = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(audit.get("events"), list):
                raise ValueError("Missing events list")
            return audit, str(path), errors
        except (OSError, ValueError) as error:
            errors.append(f"{path.name}: {error}")
    return {}, "", errors


def evaluate(run, stage, code, elapsed, started, state=""):
    audit, audit_path, warnings = audit_for(run)
    events = audit.get("events", [])
    failure = next((event for event in reversed(events) if event.get("type") == "failure"), {})
    outcome = audit.get("outcome", "")
    admission = any(e.get("type") == "benchmark-admission-complete" for e in events)
    menu = next((e for e in events if e.get("type") == "client-lifecycle" and e.get("subject") == "main-menu"), None)
    world = any(e.get("type") == "benchmark-world-complete" for e in events)
    complete = any(e.get("type") == "client-complete" for e in events)
    crashes = [str(p) for p in run.glob("crash-reports/*") if p.is_file()]
    crashes += [str(p) for p in run.glob("hs_err_pid*.log")]
    startup = None
    if menu or (stage == "admission" and admission):
        event = menu or next(e for e in events if e.get("type") == "benchmark-admission-complete")
        try:
            startup = max(0, round((datetime.fromisoformat(event["time"].replace("Z", "+00:00")).timestamp() - started) * 1000))
        except (KeyError, ValueError):
            warnings.append("Invalid readiness timestamp")
    if state:
        status, reason = state, state.lower()
    elif crashes or code != 0 or outcome == "FAILED" or failure:
        dependency_codes = {"MISSING_DEPENDENCY", "DEPENDENCY_VERSION", "INCOMPATIBLE_DEPENDENCY", "DUPLICATE_MOD_ID", "JARJAR_VERSION"}
        status = "INPUT_ERROR" if failure.get("details", {}).get("code") in dependency_codes else "FAIL"
        reason = failure.get("details", {}).get("message", "Crash report or nonzero exit")
        if "ModResolutionException" in reason:
            status = "INPUT_ERROR"
    elif stage == "admission" and outcome == "ADMITTED" and admission:
        status, reason = "PASS", "Static admission complete; lifecycle not exercised"
    elif stage in ("menu", "world") and outcome == "SUCCESS" and menu and complete and (stage != "world" or world):
        status, reason = "PASS", "Main menu and clean shutdown" if stage == "menu" else "World ticks and clean shutdown"
    else:
        status, reason = "FAIL", "Required final audit/probe evidence is missing"
    return {"stage": stage, "status": status, "exit_code": code, "duration_ms": elapsed,
            "startup_ms": startup, "audit_outcome": outcome, "audit_path": audit_path,
            "failure_code": failure.get("details", {}).get("code", ""), "reason": reason,
            "crash_reports": crashes, "audit_warnings": warnings,
            "last_phase": next((e.get("subject") for e in reversed(events) if e.get("type") == "phase"), ""),
            "run_dir": str(run), "latest_log": str(run / "logs/latest.log") if (run / "logs/latest.log").exists() else ""}


def run_stage(manifest, case, stage, run, heap, frames):
    run.mkdir(parents=True, exist_ok=False)
    (run / "mods").mkdir()
    command = command_for(manifest, case, stage, run, heap, frames)
    atomic_json(run / "launch.json", {"command": command, "inputs": case["inputs"]})
    started, monotonic = time.time(), time.monotonic()
    tree, code, state, detail = None, None, "", ""
    try:
        for item in case["inputs"]:
            destination = run / "mods" / Path(item["path"]).name
            shutil.copy2(item["path"], destination)
            if sha256(destination) != item["sha256"]:
                raise ValueError(f"Input changed since manifest validation: {item['path']}")
        (run / "options.txt").write_text("onboardAccessibility:false\nrenderDistance:4\nsimulationDistance:4\nmaxFps:60\npauseOnLostFocus:false\n", encoding="utf-8")
        started, monotonic = time.time(), time.monotonic()
        with (run / "console.log").open("wb") as output:
            tree = ProcessTree(command, run, output)
            try:
                code = tree.process.wait(timeout=case["timeout_s"])
            except subprocess.TimeoutExpired:
                state = "TIMEOUT"
    except KeyboardInterrupt:
        state = "INTERRUPTED"
    except (OSError, ValueError, RuntimeError) as error:
        state, detail = "ERROR", str(error)
    finally:
        if tree:
            try:
                tree.close()
                code = tree.process.returncode
            except (OSError, subprocess.TimeoutExpired) as error:
                state, detail = "ERROR", f"Process tree cleanup failed: {error}"
    result = evaluate(run, stage, code, round((time.monotonic() - monotonic) * 1000), started, state)
    result.update(case_id=case["case_id"], profile=case["profile"], timeout_s=case["timeout_s"], inputs=case["inputs"])
    if detail:
        result["reason"] = detail
    atomic_json(run / "result.json", result)
    return result


def save_summary(batch, results):
    atomic_json(batch / "results.json", {"schema_version": 1, "results": results})
    temporary = batch / "results.csv.tmp"
    with temporary.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)
    temporary.replace(batch / "results.csv")


def run_batch(manifest, cases, output, heap="4g", frames=10, pools=()):
    batch = output.resolve() / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    batch.mkdir(parents=True, exist_ok=False)
    atomic_json(batch / "batch.json", {"schema_version": 1, "runtime": manifest, "cases": cases, "heap": heap, "frames": frames})
    shutil.copyfile(Path(__file__), batch / "benchmark_mods.py")
    shutil.copyfile(Path(__file__).with_name("benchmark_process.py"), batch / "benchmark_process.py")
    results, interrupted = [], False
    print(f"Evidence: {batch}", flush=True)
    print("Resolving recursive required dependencies from local mod pools...", flush=True)
    try:
        cases = resolve_dependencies(manifest, cases, pools, batch / "dependencies")
    except (KeyboardInterrupt, OSError, ValueError, subprocess.TimeoutExpired) as error:
        interrupted = isinstance(error, KeyboardInterrupt)
        for case in cases:
            case["dependency_result"] = {"status": "INTERRUPTED" if interrupted else "ERROR", "reason": str(error) or "Interrupted during dependency planning"}
    atomic_json(batch / "batch.json", {"schema_version": 1, "runtime": manifest, "cases": cases, "heap": heap, "frames": frames})
    for index, case in enumerate(cases, 1):
        dependency = case["dependency_result"]
        dependency_status = "PASS" if dependency["status"] == "READY" else dependency["status"]
        result = {"case_id": case["case_id"], "stage": "dependencies", "profile": case["profile"],
                  "status": dependency_status, "reason": dependency["reason"], "failure_code": dependency.get("failure_code", ""),
                  "inputs": case["inputs"], "run_dir": str(batch / "dependencies")}
        append_result(batch, results, result)
        print(f"[{index}/{len(cases)}] {case['case_id']} dependencies: {dependency_status}", flush=True)
        blocked = "Interrupted" if interrupted else "" if dependency_status == "PASS" else dependency["reason"]
        for stage in case["stages"]:
            run = batch / f"case-{index:04d}-{case['case_id']}" / stage
            if blocked:
                result = {"case_id": case["case_id"], "stage": stage, "profile": case["profile"],
                          "status": "SKIPPED", "reason": blocked, "run_dir": str(run), "timeout_s": case["timeout_s"]}
            else:
                result = run_stage(manifest, case, stage, run, heap, frames)
                if result["status"] != "PASS":
                    blocked = f"Previous stage {stage}: {result['status']}"
                interrupted |= result["status"] == "INTERRUPTED"
            append_result(batch, results, result)
            print(f"[{index}/{len(cases)}] {case['case_id']} {stage}: {result['status']} {result.get('duration_ms', '')}ms", flush=True)
    return 130 if interrupted else 1 if any(r["status"] != "PASS" for r in results) else 0


def append_result(batch, results, result):
    results.append(result)
    with (batch / "results.ndjson").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    save_summary(batch, results)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=Path("build/benchmark/runtime.json"))
    parser.add_argument("--cases", type=Path, default=Path("benchmark/mods.csv"))
    parser.add_argument("--output", type=Path, default=Path("build/benchmark/runs"))
    parser.add_argument("--stages", default="admission,menu")
    parser.add_argument("--timeout", type=timeout_value, default=180)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--frames", type=int, default=10)
    parser.add_argument("--dry-run", action="store_true", help="Validate inputs and print commands without launching")
    parser.add_argument("--mod-pool", type=Path, action="append", help="Local JAR pool, repeatable; required dependencies only")
    options = parser.parse_args(argv)
    try:
        if not re.fullmatch(r"[1-9][0-9]*[mMgG]", options.heap) or not 1 <= options.frames <= 20000:
            raise ValueError("Heap must be e.g. 4g, frames must be 1..20000")
        cases = read_cases(options.cases.resolve(), stage_list(options.stages), options.timeout)
        manifest = load_runtime(options.runtime.resolve(), cases)
        pools = [path.resolve() for path in (options.mod_pool or [Path("mods/test-set"), Path("benchmark/dependencies")])]
        if options.dry_run:
            with tempfile.TemporaryDirectory(prefix="nf-benchmark-dependencies-") as directory:
                cases = resolve_dependencies(manifest, cases, pools, Path(directory))
            failed = False
            for index, case in enumerate(cases, 1):
                if case["dependency_result"]["status"] != "READY":
                    print(json.dumps({"case_id": case["case_id"], **case["dependency_result"]}, ensure_ascii=False))
                    failed = True
                    continue
                for stage in case["stages"]:
                    run = options.output.resolve() / "dry-run" / f"case-{index:04d}-{case['case_id']}" / stage
                    print(json.dumps({"case_id": case["case_id"], "stage": stage, "profile": case["profile"],
                                      "command": command_for(manifest, case, stage, run, options.heap, options.frames)}, ensure_ascii=False))
            return 1 if failed else 0
        return run_batch(manifest, cases, options.output, options.heap, options.frames, pools)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"benchmarkMods: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
