"""불완전 원본·해시 불일치·symlink·교체 실패가 기존 계약을 손상시키지 않아야 한다."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("sync_contracts", Path(__file__).with_name("sync_contracts.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ContractSync(unittest.TestCase):
    def setup_tree(self, root):
        source = root / "source"
        source.mkdir()
        hashes = {}
        for name in (*MODULE.SCHEMAS, MODULE.FIXTURES + "index.json"):
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("{}")
            hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        (source / "MANIFEST.json").write_text(json.dumps({"files": hashes, "revision": "sha256:" + "0" * 64}))
        backend = root / "backend"
        for app in MODULE.APPS:
            destination = backend / app / "src/test/resources/contracts"
            destination.mkdir(parents=True)
            (destination / "old.json").write_text("old")
        return source, backend

    def assert_original(self, backend):
        for app in MODULE.APPS:
            self.assertEqual((backend / app / "src/test/resources/contracts/old.json").read_text(), "old")

    def test_valid_source_installs_both_copies(self):
        with tempfile.TemporaryDirectory() as directory:
            source, backend = self.setup_tree(Path(directory))
            count, revision = MODULE.sync(source, backend)
            self.assertEqual((count, revision), (6, "sha256:" + "0" * 64))
            for app in MODULE.APPS:
                self.assertTrue((backend / app / "src/test/resources/contracts/MANIFEST.json").is_file())

    def test_missing_schema_keeps_existing_contracts(self):
        with tempfile.TemporaryDirectory() as directory:
            source, backend = self.setup_tree(Path(directory))
            (source / MODULE.SCHEMAS[0]).unlink()
            with self.assertRaises(ValueError):
                MODULE.sync(source, backend)
            self.assert_original(backend)

    def test_hash_mismatch_keeps_existing_contracts(self):
        with tempfile.TemporaryDirectory() as directory:
            source, backend = self.setup_tree(Path(directory))
            (source / MODULE.SCHEMAS[0]).write_text('{"changed":true}')
            with self.assertRaises(ValueError):
                MODULE.sync(source, backend)
            self.assert_original(backend)

    def test_source_symlink_does_not_read_outside_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, backend = self.setup_tree(root)
            outside = root / "outside.json"
            outside.write_text("{}")
            path = source / MODULE.SCHEMAS[0]
            path.unlink()
            path.symlink_to(outside)
            with self.assertRaises(ValueError):
                MODULE.sync(source, backend)
            self.assert_original(backend)

    def test_second_install_failure_rolls_back_first(self):
        with tempfile.TemporaryDirectory() as directory:
            source, backend = self.setup_tree(Path(directory))
            original_replace = Path.replace

            def fail_second(path, destination):
                if path.name == "contracts" and "auth-server" in path.parts and ".contracts-stage-" in str(path):
                    raise OSError("synthetic install failure")
                return original_replace(path, destination)

            with patch.object(Path, "replace", fail_second):
                with self.assertRaises(OSError):
                    MODULE.sync(source, backend)
            self.assert_original(backend)

    def test_failed_rollback_retains_backup_for_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            source, backend = self.setup_tree(Path(directory))
            original_replace = Path.replace

            def fail_install_and_restore(path, destination):
                if path.name == "contracts" and "auth-server" in path.parts and ".contracts-stage-" in str(path):
                    raise OSError("synthetic install failure")
                if path.name == "previous" and "api-server" in path.parts:
                    raise OSError("synthetic restore failure")
                return original_replace(path, destination)

            with patch.object(Path, "replace", fail_install_and_restore):
                with self.assertRaises(RuntimeError):
                    MODULE.sync(source, backend)
            resources = backend / "api-server/src/test/resources"
            backups = list(resources.glob(".contracts-stage-*/previous/old.json"))
            self.assertEqual(len(backups), 1)
            self.assertEqual(backups[0].read_text(), "old")


if __name__ == "__main__":
    unittest.main()
