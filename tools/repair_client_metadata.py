"""Escape raw controls in mod JSON strings, backing up and verifying JAR contents."""
import hashlib
import copy
import json
from pathlib import Path
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
mods = root / "run/client/mods"
backups = root / "run/client/mod-backups/metadata-controls"
for jar in sorted(mods.glob("*.jar")):
    with zipfile.ZipFile(jar) as source:
        if "fabric.mod.json" not in source.namelist():
            continue
        raw = source.read("fabric.mod.json")
        text = raw.decode("utf-8")
        try:
            json.loads(text)
            continue
        except json.JSONDecodeError:
            pass
        output = []
        quoted = escaped = False
        count = 0
        for char in text:
            if quoted and ord(char) < 32:
                output.append(f"\\u{ord(char):04x}")
                count += 1
            else:
                output.append(char)
            if escaped:
                escaped = False
            elif quoted and char == "\\":
                escaped = True
            elif char == '"':
                quoted = not quoted
        fixed = "".join(output).encode("utf-8")
        json.loads(fixed)
        if not count:
            raise ValueError(f"Unsupported malformed metadata: {jar}")
        backups.mkdir(parents=True, exist_ok=True)
        backup = backups / jar.name
        if backup.exists():
            assert backup.read_bytes() == jar.read_bytes(), "Backup differs from original"
        else:
            shutil.copy2(jar, backup)
        temporary = jar.with_suffix(".jar.tmp")
        with zipfile.ZipFile(temporary, "w") as target:
            for entry in source.infolist():
                target.writestr(copy.copy(entry), fixed if entry.filename == "fabric.mod.json" else source.read(entry))
        with zipfile.ZipFile(temporary) as target:
            assert target.namelist() == source.namelist()
            for entry in source.namelist():
                if entry != "fabric.mod.json":
                    assert target.read(entry) == source.read(entry), entry
        original_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    temporary.replace(jar)
    report = dict(file=jar.name, escapedControls=count, originalSha256=original_hash,
                  repairedSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  changedEntries=["fabric.mod.json"])
    (backups / (jar.name + ".json")).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))
