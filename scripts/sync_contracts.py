#!/usr/bin/env python3
"""검증한 C-02 계약을 두 앱에 준비한 뒤 교체한다. 실패하면 기존 복사본을 보존한다."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ("security-event/v2/schema.json", "anomaly-detection/v1/schema.json",
           "response-command/v1/schema.json", "response-command/v1/result.schema.json")
FIXTURES = "security-event/v2/fixtures/"
APPS = ("api-server", "auth-server")
MAX_FILE_BYTES = 4 * 1024 * 1024


def source_bytes(source: Path, relative: str) -> bytes:
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or not name.parts:
        raise ValueError("Invalid contract path")
    path = source.joinpath(*name.parts)
    # 심볼릭 링크를 통해 원본 밖 파일을 읽지 않는다.
    for candidate in (path, *path.parents):
        if candidate == source:
            break
        if candidate.is_symlink():
            raise ValueError("Contract symlink is not allowed")
    if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("Missing or oversized contract file")
    with path.open("rb") as handle:
        content = handle.read(MAX_FILE_BYTES + 1)
    if len(content) > MAX_FILE_BYTES:
        raise ValueError("Oversized contract file")
    return content


def validated_files(source: Path) -> tuple[dict[str, bytes], str]:
    manifest_bytes = source_bytes(source, "MANIFEST.json")
    manifest = json.loads(manifest_bytes)
    declared = manifest["files"]
    if not isinstance(declared, dict) or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(manifest.get("revision", ""))):
        raise ValueError("Invalid contract manifest structure")
    selected = set(SCHEMAS) | {name for name in declared if name.startswith(FIXTURES)}
    if FIXTURES + "index.json" not in selected:
        raise ValueError("Fixture index missing from manifest")
    actual = {str(path.relative_to(source).as_posix())
              for path in (source / FIXTURES).rglob("*")
              if path.is_file() or path.is_symlink()}
    if actual != {name for name in selected if name.startswith(FIXTURES)}:
        raise ValueError("Fixture tree and manifest differ")
    files = {"MANIFEST.json": manifest_bytes}
    for name in sorted(selected):
        content = source_bytes(source, name)
        if hashlib.sha256(content).hexdigest() != declared.get(name):
            raise ValueError("Contract manifest hash mismatch")
        json.loads(content)
        files[name] = content
    return files, manifest["revision"]


def sync(source: Path, backend: Path) -> tuple[int, str]:
    files, revision = validated_files(source.resolve())
    transactions = []
    committed = False
    try:
        # 두 앱 모두 stage를 완성하기 전에는 기존 contracts를 변경하지 않는다.
        for app in APPS:
            resources = backend / app / "src/test/resources"
            if not resources.resolve().is_relative_to(backend.resolve()) or any(
                    p.is_symlink() for p in (resources, *resources.parents) if p != backend and p.is_relative_to(backend)):
                raise ValueError("App test resource ancestor symlink is not allowed")
            if not resources.is_dir() or resources.is_symlink():
                raise ValueError("App test resources missing or symlinked")
            destination = resources / "contracts"
            if destination.is_symlink():
                raise ValueError("Contract destination symlink is not allowed")
            stage_root = Path(tempfile.mkdtemp(prefix=".contracts-stage-", dir=resources))
            record = {"destination": destination, "root": stage_root,
                      "backup": stage_root / "previous", "installed": False}
            transactions.append(record)
            stage = stage_root / "contracts"
            stage.mkdir()
            for name, content in files.items():
                target = stage / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
        for record in transactions:
            destination = record["destination"]
            if destination.exists():
                destination.replace(record["backup"])
            (record["root"] / "contracts").replace(destination)
            record["installed"] = True
        committed = True
    except Exception:
        # 교체 실패 시 기존 복사본을 복원한다. 계약 값은 출력하지 않는다.
        rollback_failed = False
        for record in reversed(transactions):
            destination = record["destination"]
            try:
                if record["installed"]:
                    shutil.rmtree(destination)
                if record["backup"].exists():
                    record["backup"].replace(destination)
            except OSError:
                rollback_failed = True
        if rollback_failed:
            raise RuntimeError("Rollback incomplete; staged backups retained") from None
        raise
    finally:
        for record in transactions:
            # 복원 실패 backup은 수동 복구를 위해 지우지 않는다.
            if committed or not record["backup"].exists():
                shutil.rmtree(record["root"], ignore_errors=True)
    return len(files), revision


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path,
                        default=Path(os.environ.get("CONTRACTS_SRC", ROOT.parent / "log-pipeline/contracts")))
    args = parser.parse_args()
    try:
        count, revision = sync(args.source, ROOT)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        print("Contract sync failed; raw diagnostics suppressed. Incomplete rollback backups stay in .contracts-stage-*/previous")
        return 1
    for app in APPS:
        print(f"{app}: {count} files")
    print("revision: " + revision)
    print("revision 변경 시 각 앱 ContractRevisionTest pin도 함께 확인한다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
