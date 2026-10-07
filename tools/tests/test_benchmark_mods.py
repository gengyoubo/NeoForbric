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

    def test_runtime_dependency_rejection_is_not_counted_as_nf_incompatibility(self):
        for code in ("FABRIC_DEPENDENCY", "JARJAR_VERSION", "ORDER_CYCLE"):
            self.audit("FAILED", [{"type": "failure", "phase": "RESOLVE", "details": {"code": code, "message": "input conflict"}}])
            self.assertEqual("INPUT_ERROR", self.verdict()["status"])
        self.audit("FAILED", [{"type": "failure", "phase": "PREPARE", "details": {"code": "ORDER_CYCLE", "message": "transformer conflict"}}])
        self.assertEqual("FAIL", self.verdict()["status"])

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

    def test_locked_summary_keeps_snapshot_and_does_not_abort(self):
        replace = Path.replace

        def locked(path, target):
            if Path(target).name in ("results.csv", "results.json"):
                raise PermissionError("opened in Excel")
            return replace(path, target)

        with patch.object(Path, "replace", locked):
            bench.save_summary(self.root, [{"case_id": "sample", "status": "PASS"}])
        self.assertEqual(1, len(list(self.root.glob("results-*.json"))))
        self.assertEqual(1, len(list(self.root.glob("results-*.csv"))))

    def test_discard_keeps_original_and_changed_copies(self):
        original = self.root / "original.jar"
        original.write_bytes(b"original")
        run = self.root / "run"
        (run / "mods").mkdir(parents=True)
        copy = run / "mods/original.jar"
        copy.write_bytes(b"original")
        result = {"run_dir": str(run), "inputs": [{"path": str(original), "sha256": bench.sha256(original)}]}
        bench.discard_mod_copies(result)
        self.assertTrue(original.exists())
        self.assertFalse(copy.exists())
        copy.write_bytes(b"changed by mod")
        bench.discard_mod_copies(result)
        self.assertTrue(copy.exists())

    def resume_fixture(self):
        runtime = self.root / "pinned-runtime.jar"
        runtime.write_bytes(b"pinned runtime")
        batch = self.root / "batch"
        batch.mkdir()
        case = {"case_id": "sample", "profile": "fabric", "inputs": [],
                "stages": ["admission", "menu"], "timeout_s": 180,
                "dependency_result": {"status": "READY", "reason": "complete"}}
        bench.atomic_json(batch / "batch.json", {"schema_version": 1,
                          "runtime": {"file_sha256": {str(runtime): bench.sha256(runtime)}},
                          "cases": [case], "heap": "4g", "frames": 10})
        rows = []
        bench.append_result(batch, rows, {"case_id": "sample", "stage": "dependencies", "status": "PASS"})
        return batch, case, rows

    def fake_resume_stage(self, manifest, case, stage, run, heap, frames):
        return {"case_id": case["case_id"], "stage": stage, "status": "PASS", "run_dir": str(run)}

    def test_resume_recovers_orphan_pass_then_runs_only_menu(self):
        batch, case, rows = self.resume_fixture()
        run = batch / "case-0001-sample/admission"
        run.mkdir(parents=True)
        bench.atomic_json(run / "audit.json", {"outcome": "ADMITTED", "events": [{"type": "benchmark-admission-complete"}]})
        bench.atomic_json(run / "result.json", {"case_id": "sample", "stage": "admission", "profile": "fabric",
                          "inputs": [], "status": "PASS", "exit_code": 0, "duration_ms": 10, "run_dir": str(run)})
        with patch.object(bench, "run_stage", side_effect=self.fake_resume_stage) as launch:
            self.assertEqual(0, bench.resume_batch(batch))
        self.assertEqual(1, launch.call_count)
        self.assertEqual("menu", launch.call_args.args[2])
        saved = json.loads((batch / "results.json").read_text())["results"]
        self.assertEqual(3, len(saved))
        self.assertTrue(saved[1]["recovered_on_resume"])
        with patch.object(bench, "run_stage") as launch:
            self.assertEqual(0, bench.resume_batch(batch))
        launch.assert_not_called()

    def test_resume_keeps_failure_and_gates_menu(self):
        batch, case, rows = self.resume_fixture()
        bench.append_result(batch, rows, {"case_id": "sample", "stage": "admission", "status": "FAIL"})
        with patch.object(bench, "run_stage") as launch:
            self.assertEqual(1, bench.resume_batch(batch))
        launch.assert_not_called()
        saved = json.loads((batch / "results.json").read_text())["results"]
        self.assertEqual(["PASS", "FAIL", "SKIPPED"], [r["status"] for r in saved])

    def test_resume_retries_interrupted_in_new_dir_without_double_counting(self):
        batch, case, rows = self.resume_fixture()
        bench.append_result(batch, rows, {"case_id": "sample", "stage": "admission", "status": "INTERRUPTED"})
        bench.append_result(batch, rows, {"case_id": "sample", "stage": "menu", "status": "SKIPPED"})
        run = batch / "case-0001-sample/admission"
        run.mkdir(parents=True)
        (run / "original-evidence.txt").write_text("keep")
        with patch.object(bench, "run_stage", side_effect=self.fake_resume_stage) as launch:
            self.assertEqual(0, bench.resume_batch(batch))
        self.assertEqual(2, launch.call_count)
        self.assertEqual("admission-attempt002", launch.call_args_list[0].args[3].name)
        self.assertEqual("keep", (run / "original-evidence.txt").read_text())
        self.assertEqual(3, len(json.loads((batch / "results.json").read_text())["results"]))
        self.assertEqual(5, len((batch / "results.ndjson").read_text().splitlines()))

    def test_resume_rejects_changed_runtime_before_any_launch(self):
        batch, case, rows = self.resume_fixture()
        (self.root / "pinned-runtime.jar").write_bytes(b"new build")
        with patch.object(bench, "run_stage") as launch:
            with self.assertRaisesRegex(ValueError, "changed or missing"):
                bench.resume_batch(batch)
        launch.assert_not_called()

    def test_resume_rejects_orphan_pass_without_audit(self):
        batch, case, rows = self.resume_fixture()
        run = batch / "case-0001-sample/admission"
        run.mkdir(parents=True)
        bench.atomic_json(run / "result.json", {"case_id": "sample", "stage": "admission", "profile": "fabric",
                          "inputs": [], "status": "PASS", "exit_code": 0, "run_dir": str(run)})
        with self.assertRaisesRegex(ValueError, "lacks valid audit"):
            bench.resume_batch(batch)

    def test_resume_dry_run_and_truncated_tail_recovery(self):
        batch, case, rows = self.resume_fixture()
        journal = batch / "results.ndjson"
        with journal.open("ab") as stream:
            stream.write(b'{"case_id":')
        original = journal.read_bytes()
        with patch.object(bench, "run_stage") as launch:
            self.assertEqual(0, bench.resume_batch(batch, dry_run=True))
        launch.assert_not_called()
        self.assertEqual(original, journal.read_bytes())
        with patch.object(bench, "run_stage", side_effect=self.fake_resume_stage):
            self.assertEqual(0, bench.resume_batch(batch))
        self.assertEqual(b'{"case_id":', next(batch.glob("journal-tail-*.bin")).read_bytes())
        self.assertEqual(3, len([json.loads(line) for line in journal.read_text().splitlines()]))

    def test_batch_lock_rejects_second_runner_and_releases(self):
        with bench.batch_lock(self.root):
            with self.assertRaisesRegex(ValueError, "Another runner"):
                with bench.batch_lock(self.root):
                    self.fail("Second runner acquired lock")
        with bench.batch_lock(self.root):
            pass

    def test_result_write_retries_and_persistent_lock_uses_readable_fallback(self):
        path = self.root / "result.json"
        path.write_text('{"status":"old"}')
        replace = Path.replace
        attempts = []

        def locked(source, target):
            if Path(target) == path:
                attempts.append(target)
                raise PermissionError("locked result")
            return replace(source, target)

        with patch.object(Path, "replace", locked), patch.object(bench.time, "sleep"):
            saved = bench.atomic_json(path, {"status": "PASS"}, fallback=True)
        self.assertEqual(5, len(attempts))
        self.assertNotEqual(path, saved)
        self.assertEqual("PASS", bench.saved_json(path)["status"])
        self.assertEqual("old", json.loads(path.read_text())["status"])

        with patch.object(Path, "replace", side_effect=[PermissionError("transient"), path]), patch.object(bench.time, "sleep"):
            self.assertEqual(path, bench.atomic_json(path, {"status": "PASS"}, fallback=True))

    def test_low_disk_never_launches_or_reports_an_nf_failure(self):
        cases = [{"case_id": "sample", "profile": "fabric", "inputs": [], "stages": bench.STAGES, "timeout_s": 1,
                  "dependency_result": {"status": "READY", "reason": "complete"}}]
        with patch.object(bench, "resolve_dependencies", return_value=cases), patch.object(bench.shutil, "disk_usage") as usage, patch.object(bench, "run_stage") as launch:
            usage.return_value.free = 0
            self.assertEqual(3, bench.run_batch({}, cases, self.root))
        launch.assert_not_called()
        batch = next(self.root.iterdir())
        self.assertEqual("STOPPED_LOW_DISK", json.loads((batch / "status.json").read_text())["status"])

    def test_inventory_excludes_connector_generated_copies(self):
        from benchmark_inventory import inventory
        self.jar("sample.jar", "fabric.mod.json")
        self.jar(".connector/sample_mapped.jar", "fabric.mod.json")
        report = inventory(self.root, self.root / "cases.csv", self.root / "inventory.json")
        self.assertEqual(1, report["jar_count"])
        self.assertEqual(1, len(json.loads((self.root / "inventory.json").read_text())["excluded_nested_jars"]))

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
