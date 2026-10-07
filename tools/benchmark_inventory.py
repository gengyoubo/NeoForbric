"""Inventory a local JAR sample directory and generate one benchmark case per file."""
import argparse
from collections import Counter
import csv
import json
import os
from pathlib import Path
import re
import sys
import zipfile

from benchmark_mods import infer_profile


def inventory(directory, csv_path, json_path):
    # Match the launcher's root mods directory; .connector contains generated
    # remapped copies rather than additional original samples.
    files = sorted(directory.iterdir(), key=lambda p: p.name.casefold())
    jars = [p for p in files if p.is_file() and p.suffix.lower() == ".jar"]
    counts, records = Counter(), []
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["case_id", "mods", "dependencies", "profile", "stages", "timeout_s", "enabled"])
        for index, path in enumerate(jars, 1):
            error = ""
            try:
                profile = infer_profile([{"path": str(path)}])
                with zipfile.ZipFile(path) as jar:
                    names = set(jar.namelist())
                descriptors = sorted(names.intersection({"fabric.mod.json", "neoforbric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"}))
                category = profile if descriptors else "no-descriptor"
            except (OSError, zipfile.BadZipFile) as invalid:
                profile, category, descriptors, error = "fabric", "invalid-jar", [], str(invalid)
            counts[category] += 1
            slug = re.sub(r"[^a-zA-Z0-9._-]+", "-", path.stem).strip("-._")[:80] or "sample"
            identifier = f"mod{index:04d}-{slug}"
            relative = Path(os.path.relpath(path, csv_path.parent)).as_posix()
            writer.writerow([identifier, relative, "", profile, "", 180, "true"])
            records.append({"case_id": identifier, "filename": path.name, "path": str(path),
                            "profile": profile, "descriptors": descriptors, "size_bytes": path.stat().st_size,
                            "inventory_error": error})
    report = {"schema_version": 1, "directory": str(directory), "jar_count": len(jars), "profile_counts": dict(counts),
              "total_jar_bytes": sum(r["size_bytes"] for r in records),
              "excluded_nested_jars": [str(p) for p in directory.rglob("*.jar") if p.parent != directory],
              "other_files": [{"path": str(p), "size_bytes": p.stat().st_size} for p in files if p.is_file() and p.suffix.lower() != ".jar"],
              "cases_csv": str(csv_path), "samples": records}
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {key: report[key] for key in ("jar_count", "profile_counts", "total_jar_bytes", "cases_csv")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--csv", type=Path, default=Path("benchmark/experiment.csv"))
    parser.add_argument("--json", type=Path, default=Path("build/benchmark/inventory.json"))
    args = parser.parse_args()
    if not args.directory.is_dir():
        parser.error("Sample directory does not exist")
    print(json.dumps(inventory(args.directory.resolve(), args.csv.resolve(), args.json.resolve()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.exit(main())
