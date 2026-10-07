import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_mods as bench
from benchmark_process import ProcessTree


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def audit(self, outcome="SUCCESS", events=None, filename="audit.json"):
        (self.root / filename).write_text(json.dumps({"outcome": outcome, "events": events or []}), encoding="utf-8")

    def events(self, world=False):
        events = [{"type": "client-lifecycle", "subject": "main-menu", "time": "2026-10-07T15:00:01Z"},
                  {"type": "client-complete", "subject": "Minecraft"}]
        if world:
            events.append({"type": "benchmark-world-complete"})
        return events

    def verdict(self, stage="menu", code=0, state=""):
        return bench.evaluate(self.root, stage, code, 123, 1791385200, state)

    def test_exit_zero_and_running_audit_do_not_pass(self):
        self.assertEqual("FAIL", self.verdict()["status"])
        self.audit("RUNNING", self.events())
        self.assertEqual("FAIL", self.verdict()["status"])
        self.audit("SUCCESS", self.events()[:1])
        self.assertEqual("FAIL", self.verdict()["status"])

    def test_complete_menu_requires_clean_exit_and_no_crash(self):
        self.audit(events=self.events())
        self.assertEqual("PASS", self.verdict()["status"])
        self.assertEqual("FAIL", self.verdict(code=1)["status"])
        self.assertEqual("TIMEOUT", self.verdict(state="TIMEOUT")["status"])
        (self.root / "crash-reports").mkdir()
        (self.root / "crash-reports/crash.txt").write_text("crash")
        self.assertEqual("FAIL", self.verdict()["status"])

    def test_world_needs_its_own_evidence(self):
        self.audit(events=self.events())
        self.assertEqual("FAIL", self.verdict("world")["status"])
        self.audit(events=self.events(True))
        self.assertEqual("PASS", self.verdict("world")["status"])

    def test_admission_is_a_separate_outcome(self):
        events = [{"type": "benchmark-admission-complete", "time": "2026-10-07T15:00:01Z"}]
        self.audit("ADMITTED", events)
        self.assertEqual("PASS", self.verdict("admission")["status"])
        self.assertEqual("FAIL", self.verdict("menu")["status"])
        self.audit("SUCCESS", events)
        self.assertEqual("FAIL", self.verdict("admission")["status"])

    def test_latest_fallback_and_malformed_primary(self):
        self.audit("RUNNING", self.events())
        self.audit("SUCCESS", self.events(), "audit.json.fallback-test.json")
        os.utime(self.root / "audit.json", (1, 1))
        result = self.verdict()
        self.assertEqual("PASS", result["status"])
        self.assertTrue(result["audit_path"].endswith("fallback-test.json"))
        (self.root / "audit.json").write_text("{broken")
        result = self.verdict()
        self.assertEqual("PASS", result["status"])
        self.assertTrue(result["audit_warnings"])

    def test_failure_code_survives_summary(self):
        self.audit("FAILED", [{"type": "failure", "details": {"code": "MISSING_DEPENDENCY", "message": "missing core"}}])
        result = self.verdict()
        self.assertEqual("MISSING_DEPENDENCY", result["failure_code"])
        self.assertEqual("INPUT_ERROR", result["status"])
        self.assertEqual("missing core", result["reason"])

    def jar(self, name, descriptor):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr(descriptor, "{}")
        return path

    def test_relative_inputs_and_dependency_profile_selection(self):
        self.jar("mods/sample.jar", "fabric.mod.json")
        self.jar("deps/core.jar", "META-INF/neoforge.mods.toml")
        path = self.root / "cases.csv"
        path.write_text("case_id,mods,dependencies,profile\nsample,mods/sample.jar,deps/core.jar,auto\n", encoding="utf-8")
        cases = bench.read_cases(path, ("admission", "menu"), 180)
        self.assertEqual("neoforge", cases[0]["profile"])
        self.assertEqual(str(self.root / "mods/sample.jar"), cases[0]["inputs"][0]["path"])
        self.assertEqual(64, len(cases[0]["inputs"][0]["sha256"]))

    def test_bad_case_id_duplicate_filename_and_missing_jar(self):
        self.jar("mods/x.jar", "fabric.mod.json")
        self.jar("deps/x.jar", "fabric.mod.json")
        path = self.root / "cases.csv"
        for row in ("../escape,mods/x.jar,", "same,mods/x.jar,deps/x.jar", "missing,no.jar,"):
            with self.subTest(row=row):
                path.write_text("case_id,mods,dependencies\n" + row + "\n")
                with self.assertRaises(ValueError):
                    bench.read_cases(path, ("admission",), 180)

    def test_stage_and_timeout_validation(self):
        for value in ("menu,admission", "world,world", "", "unknown"):
            with self.assertRaises(ValueError):
                bench.stage_list(value)
        for value in ("nan", "inf", "0", "-1"):
            with self.assertRaises(ValueError):
                bench.timeout_value(value)

    def test_batch_gates_stages_and_continues_other_cases(self):
        cases = [{"case_id": name, "profile": "fabric", "inputs": [], "stages": bench.STAGES, "timeout_s": 1}
                 for name in ("bad", "good")]

        def fake_run(manifest, case, stage, run, heap, frames):
            return {"case_id": case["case_id"], "profile": "fabric", "stage": stage,
                    "status": "FAIL" if case["case_id"] == "bad" else "PASS", "run_dir": str(run)}

        for case in cases:
            case["dependency_result"] = {"status": "READY", "reason": "complete"}
        with patch.object(bench, "resolve_dependencies", return_value=cases), patch.object(bench, "run_stage", side_effect=fake_run) as mock:
            self.assertEqual(1, bench.run_batch({}, cases, self.root))
        self.assertEqual(4, mock.call_count)
        batch = next(self.root.iterdir())
        rows = json.loads((batch / "results.json").read_text())["results"]
        self.assertEqual(["PASS", "FAIL", "SKIPPED", "SKIPPED", "PASS", "PASS", "PASS", "PASS"], [r["status"] for r in rows])
        self.assertEqual(8, len((batch / "results.ndjson").read_text().splitlines()))

    def test_missing_recursive_dependency_skips_client_without_compatibility_failure(self):
        cases = [{"case_id": "missing", "profile": "fabric", "inputs": [], "stages": bench.STAGES, "timeout_s": 1,
                  "dependency_result": {"status": "INPUT_ERROR", "failure_code": "INPUT_DEPENDENCY", "reason": "core -> library >=2"}}]
        with patch.object(bench, "resolve_dependencies", return_value=cases), patch.object(bench, "run_stage") as mock:
            self.assertEqual(1, bench.run_batch({}, cases, self.root))
        mock.assert_not_called()
        rows = json.loads((next(self.root.iterdir()) / "results.json").read_text())["results"]
        self.assertEqual(["INPUT_ERROR", "SKIPPED", "SKIPPED", "SKIPPED"], [r["status"] for r in rows])

    def test_timeout_saved_and_tree_closed(self):
        case = {"case_id": "timeout", "inputs": [], "profile": "fabric", "timeout_s": 0.15}
        with patch.object(bench, "command_for", return_value=[sys.executable, "-c", "import time; time.sleep(30)"]):
            result = bench.run_stage({}, case, "admission", self.root / "run", "4g", 10)
        self.assertEqual("TIMEOUT", result["status"])
        self.assertIsNotNone(result["exit_code"])
        self.assertTrue((self.root / "run/result.json").exists())

    def test_process_tree_cleanup_after_parent_exit_and_timeout(self):
        for parent_exits in (True, False):
            with self.subTest(parent_exits=parent_exits):
                ready = self.root / f"ready-{parent_exits}"
                survived = self.root / f"survived-{parent_exits}"
                child = ("import pathlib,time; "
                         f"pathlib.Path({str(ready)!r}).write_text('ready'); "
                         f"time.sleep(1); pathlib.Path({str(survived)!r}).write_text('leaked')")
                parent = ("import subprocess,sys,time,pathlib; "
                          f"subprocess.Popen([sys.executable,'-c',{child!r}]); "
                          f"p=pathlib.Path({str(ready)!r}); "
                          "\nwhile not p.exists(): time.sleep(.01)\n" +
                          ("" if parent_exits else "time.sleep(30)"))
                with (self.root / "output.log").open("wb") as output:
                    tree = ProcessTree([sys.executable, "-c", parent], self.root, output)
                    try:
                        deadline = time.monotonic() + 5
                        while not ready.exists() and time.monotonic() < deadline:
                            time.sleep(.02)
                        self.assertTrue(ready.exists())
                        if parent_exits:
                            self.assertEqual(0, tree.process.wait(timeout=5))
                    finally:
                        tree.close()
                time.sleep(1.1)
                self.assertFalse(survived.exists(), "Child survived process tree cleanup")


if __name__ == "__main__":
    unittest.main()
