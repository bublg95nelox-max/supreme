"""
Unit tests for SyncEngine and Manifest Generator.
"""
import os
import json
import unittest
import tempfile
from pathlib import Path

from tools.generate_manifest import generate_manifest, compute_sha256
from core.sync_engine import (
    SyncEngine,
    compute_file_sha256,
    safe_resolve_path,
    check_free_space,
    cleanup_temp_files,
)


class TestSyncEngine(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source_dir = self.root / "source"
        self.client_dir = self.root / "client"

        self.source_dir.mkdir()
        self.client_dir.mkdir()

        # Create sample mods and configs in source
        (self.source_dir / "mods").mkdir()
        (self.source_dir / "config").mkdir()

        with open(self.source_dir / "mods" / "sample-mod.jar", "w") as f:
            f.write("mod-content-v1")

        with open(self.source_dir / "config" / "mod.cfg", "w") as f:
            f.write("default-config")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_generate_manifest(self):
        manifest = generate_manifest(
            source_dir=self.source_dir,
            base_download_url="https://github.com/myorg/repo/releases/download/v1.0",
            minecraft_version="1.21.1",
            neoforge_version="21.1.72",
        )

        self.assertEqual(manifest["minecraft_version"], "1.21.1")
        self.assertEqual(manifest["loader"]["version"], "21.1.72")
        self.assertEqual(len(manifest["files"]), 2)

        mod_entry = next(f for f in manifest["files"] if f["path"] == "mods/sample-mod.jar")
        self.assertTrue(mod_entry["overwrite"])
        self.assertTrue(mod_entry["url"].endswith("sample-mod.jar"))

        cfg_entry = next(f for f in manifest["files"] if f["path"] == "config/mod.cfg")
        self.assertFalse(cfg_entry["overwrite"])

    def test_plan_sync_detects_missing_and_obsolete(self):
        manifest = generate_manifest(self.source_dir, "http://example.com")
        engine = SyncEngine(self.client_dir)

        # Create an obsolete mod in client mods
        (self.client_dir / "mods").mkdir(parents=True)
        obsolete_mod = self.client_dir / "mods" / "old-deleted-mod.jar"
        obsolete_mod.write_text("old")

        to_download, obsolete = engine.plan_sync(manifest)

        # Both sample-mod.jar and mod.cfg should be planned for download
        self.assertEqual(len(to_download), 2)
        # old-deleted-mod.jar should be detected as obsolete
        self.assertEqual(len(obsolete), 1)
        self.assertEqual(obsolete[0].name, "old-deleted-mod.jar")

    def test_config_not_overwritten_if_modified(self):
        manifest = generate_manifest(self.source_dir, "http://example.com")
        engine = SyncEngine(self.client_dir)

        # Player modified their config
        cfg_path = self.client_dir / "config" / "mod.cfg"
        cfg_path.parent.mkdir(parents=True)
        cfg_path.write_text("user-custom-setting=true")

        to_download, _ = engine.plan_sync(manifest)

        # mod.cfg has overwrite=False, so it should NOT be planned for re-download
        download_paths = [f["path"] for f in to_download]
        self.assertNotIn("config/mod.cfg", download_paths)
        self.assertIn("mods/sample-mod.jar", download_paths)

    def test_generate_manifest_preserve_paths(self):
        manifest = generate_manifest(
            self.source_dir,
            "http://example.com/repo",
            preserve_paths=True,
        )
        mod_entry = next(f for f in manifest["files"] if f["path"] == "mods/sample-mod.jar")
        self.assertEqual(mod_entry["url"], "http://example.com/repo/mods/sample-mod.jar")

    def test_sync_from_url_alias(self):
        engine = SyncEngine(self.client_dir)
        self.assertTrue(hasattr(engine, "sync_from_url"))
        self.assertEqual(engine.sync, engine.sync_from_url)

    def test_path_traversal_detection(self):
        base = self.client_dir
        with self.assertRaises(ValueError):
            safe_resolve_path(base, "../../../etc/passwd")

        with self.assertRaises(ValueError):
            safe_resolve_path(base, "mods/../../secret.txt")

        # Valid nested path
        resolved = safe_resolve_path(base, "mods/subdir/mod.jar")
        self.assertEqual(resolved, (base / "mods" / "subdir" / "mod.jar").resolve())

    def test_check_free_space_insufficient(self):
        # Request an impossibly huge amount of disk space
        with self.assertRaises(IOError) as ctx:
            check_free_space(self.client_dir, 100 * 1024 * 1024 * 1024 * 1024)  # 100 TB
        self.assertIn("Недостаточно свободного места", str(ctx.exception))

    def test_cleanup_temp_files(self):
        # Create staging and .download files
        staging = self.client_dir / ".sync_staging"
        staging.mkdir()
        (staging / "junk.tmp").write_text("junk")
        (self.client_dir / "mods").mkdir(exist_ok=True)
        (self.client_dir / "mods" / "unfinished.jar.download").write_text("half")

        cleanup_temp_files(self.client_dir)

        self.assertFalse(staging.exists())
        self.assertFalse((self.client_dir / "mods" / "unfinished.jar.download").exists())

    def test_two_phase_transactional_safety_preserves_installation_on_failure(self):
        """
        Tests that if a download fails midway, the existing installation
        and existing working mods are NOT deleted or corrupted!
        """
        # Pre-install working mods
        mods_dir = self.client_dir / "mods"
        mods_dir.mkdir(parents=True, exist_ok=True)
        existing_working_mod = mods_dir / "existing-mod.jar"
        existing_working_mod.write_text("working-v1")
        obsolete_mod = mods_dir / "old-obsolete.jar"
        obsolete_mod.write_text("old-mod")

        engine = SyncEngine(self.client_dir)

        # Manifest with 2 files: one good, one that will fail (invalid URL/404)
        sample_file = self.source_dir / "mods" / "sample-mod.jar"
        good_url = f"file://{sample_file.resolve().as_posix()}"
        bad_url = "http://127.0.0.1:59999/non-existent-mod.jar"

        manifest = {
            "minecraft_version": "1.21.1",
            "files": [
                {
                    "path": "mods/sample-mod.jar",
                    "sha256": compute_sha256(sample_file),
                    "size": sample_file.stat().st_size,
                    "url": good_url,
                    "overwrite": True,
                },
                {
                    "path": "mods/broken-mod.jar",
                    "sha256": "0" * 64,
                    "size": 1000,
                    "url": bad_url,
                    "overwrite": True,
                },
            ],
        }

        manifest_path = self.root / "broken_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f)

        # Execution must fail
        with self.assertRaises(Exception):
            engine.sync(str(manifest_path))

        # CRUCIAL ASSERTION: The existing installation must NOT be damaged!
        self.assertTrue(
            existing_working_mod.exists(),
            "Existing working mod must NOT be deleted when an update fails!",
        )
        self.assertEqual(existing_working_mod.read_text(), "working-v1")
        self.assertTrue(
            obsolete_mod.exists(),
            "Obsolete mod must NOT be deleted until all new files succeed!",
        )
        # Staging directory must be cleaned up
        self.assertFalse((self.client_dir / ".sync_staging").exists())

    def test_full_successful_sync_end_to_end(self):
        """
        Tests complete two-phase sync: files are staged, verified, committed,
        and obsolete mods are cleaned up.
        """
        mods_dir = self.client_dir / "mods"
        mods_dir.mkdir(parents=True, exist_ok=True)
        obsolete_mod = mods_dir / "old-obsolete.jar"
        obsolete_mod.write_text("old-mod")

        engine = SyncEngine(self.client_dir)

        sample_file = self.source_dir / "mods" / "sample-mod.jar"
        good_url = f"file://{sample_file.resolve().as_posix()}"

        manifest = {
            "minecraft_version": "1.21.1",
            "files": [
                {
                    "path": "mods/sample-mod.jar",
                    "sha256": compute_sha256(sample_file),
                    "size": sample_file.stat().st_size,
                    "url": good_url,
                    "overwrite": True,
                }
            ],
        }

        manifest_path = self.root / "good_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f)

        res = engine.sync(str(manifest_path))
        self.assertEqual(res["minecraft_version"], "1.21.1")

        # Check that new mod is committed
        installed_mod = mods_dir / "sample-mod.jar"
        self.assertTrue(installed_mod.exists())
        self.assertEqual(installed_mod.read_text(), "mod-content-v1")

        # Check that obsolete mod was removed
        self.assertFalse(obsolete_mod.exists())
        self.assertFalse((self.client_dir / ".sync_staging").exists())


if __name__ == "__main__":
    unittest.main()
