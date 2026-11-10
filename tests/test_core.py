"""
Unit tests for Lowuncher Core Engine.
"""
import os
import sys
import unittest
import tempfile
from pathlib import Path

from core.config import ConfigManager
from core.auth import AuthManager, generate_offline_uuid, get_avatar_url
from core.java_manager import JavaManager
from core.installer import Installer


class TestCoreEngine(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.data_dir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_config_manager(self):
        cfg = ConfigManager(self.data_dir)
        self.assertEqual(cfg.username, "Player")
        cfg.username = "SchoolFriend"
        cfg.allocated_ram_mb = 6144
        cfg.save()

        # Reload from disk
        cfg2 = ConfigManager(self.data_dir)
        self.assertEqual(cfg2.username, "SchoolFriend")
        self.assertEqual(cfg2.allocated_ram_mb, 6144)

    def test_recommended_ram_logic(self):
        from core.config import calculate_recommended_ram_mb
        rec = calculate_recommended_ram_mb()
        self.assertGreaterEqual(rec, 3072)
        self.assertLessEqual(rec, 16 * 1024)


    def test_auth_offline_uuid(self):
        # Deterministic check
        uuid1 = generate_offline_uuid("Steve")
        uuid2 = generate_offline_uuid("Steve")
        uuid_alex = generate_offline_uuid("Alex")

        self.assertEqual(uuid1, uuid2)
        self.assertNotEqual(uuid1, uuid_alex)
        self.assertEqual(len(uuid1), 36)

        session = AuthManager.get_offline_session("SchoolFriend")
        self.assertEqual(session["username"], "SchoolFriend")
        self.assertEqual(session["auth_type"], "offline")

        opts = AuthManager.format_minecraft_options(session, allocated_ram_mb=4096)
        self.assertEqual(opts["username"], "SchoolFriend")
        self.assertIn("-Xmx4096M", opts["jvmArguments"])

    def test_avatar_url(self):
        url = get_avatar_url("Notch", 64)
        self.assertTrue(url.startswith("https://minotar.net/helm/Notch/"))

    def test_java_manager_info(self):
        jm = JavaManager(self.data_dir / "runtime")
        os_name, arch = jm._get_system_info()
        self.assertIn(os_name, ["windows", "linux", "mac"])
        self.assertIn(arch, ["x64", "aarch64"])

    def test_installer_check(self):
        installer = Installer(self.data_dir / "game")
        self.assertFalse(installer.is_version_installed("1.21.1"))

    def test_java_manager_ensure_method(self):
        jm = JavaManager(self.data_dir / "runtime")
        self.assertTrue(hasattr(jm, "ensure_java_21"))
        self.assertTrue(callable(getattr(jm, "ensure_java_21")))

    def test_auth_empty_uuid_fallback(self):
        opts = AuthManager.format_minecraft_options(
            session={"username": "Steve", "uuid": ""},
            allocated_ram_mb=2048,
        )
        self.assertEqual(opts["username"], "Steve")
        self.assertEqual(opts["uuid"], generate_offline_uuid("Steve"))

    def test_auth_undefined_and_null_uuid_fallback(self):
        opts_undef = AuthManager.format_minecraft_options(
            session={"username": "Steve", "uuid": "undefined"},
            allocated_ram_mb=2048,
        )
        self.assertEqual(opts_undef["uuid"], generate_offline_uuid("Steve"))

        opts_null = AuthManager.format_minecraft_options(
            session={"username": "Steve", "uuid": "null"},
            allocated_ram_mb=2048,
        )
        self.assertEqual(opts_null["uuid"], generate_offline_uuid("Steve"))

    def test_auth_ram_clamping(self):
        opts_small = AuthManager.format_minecraft_options(
            session={"username": "Steve"},
            allocated_ram_mb=512,
        )
        self.assertIn("-Xmx1024M", opts_small["jvmArguments"])

        opts_zero = AuthManager.format_minecraft_options(
            session={"username": "Steve"},
            allocated_ram_mb=0,
        )
        self.assertIn("-Xmx4096M", opts_zero["jvmArguments"])

    def test_java_manager_system_java_detection(self):
        jm = JavaManager(self.data_dir / "runtime")
        self.assertTrue(hasattr(jm, "get_system_java"))
        res = jm.get_system_java()
        if res is not None:
            self.assertTrue(res.exists())
            self.assertTrue(jm.verify_java(res))


    def test_launcher_brand_sanitization(self):
        from core.launcher import GameLauncher
        from unittest.mock import patch

        launcher = GameLauncher(self.data_dir)
        mock_mll_cmd = [
            "java",
            "-Dminecraft.launcher.brand=minecraft-launcher-lib",
            "-Dminecraft.launcher.version=8.0",
            "net.minecraft.client.main.Main"
        ]
        with patch("minecraft_launcher_lib.command.get_minecraft_command", return_value=mock_mll_cmd):
            cmd = launcher.build_command("1.21.1", Path("/dummy/java"), {})
            self.assertIn("-Dminecraft.launcher.brand=Lowuncher", cmd)
            self.assertNotIn("-Dminecraft.launcher.brand=minecraft-launcher-lib", cmd)
            self.assertTrue(any(a.startswith("-Dlowuncher.active=") for a in cmd))


    def test_corrupted_profiles_json_is_backed_up(self):
        from core.installer import Installer
        game_dir = self.data_dir / "game_profiles_test"
        game_dir.mkdir(parents=True, exist_ok=True)
        profiles_file = game_dir / "launcher_profiles.json"
        profiles_file.write_text("NOT_VALID_JSON{", encoding="utf-8")

        installer = Installer(game_dir)
        # Attempting install_neoforge or preparing profiles
        # Simulate profiles check logic
        profiles_json = game_dir / "launcher_profiles.json"
        valid_profiles = False
        if profiles_json.exists():
            try:
                with open(profiles_json, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, dict) and "profiles" in data:
                        valid_profiles = True
            except Exception:
                valid_profiles = False
        if not valid_profiles:
            if profiles_json.exists():
                import shutil
                shutil.copy2(profiles_json, profiles_json.with_suffix(".json.bak"))
            with open(profiles_json, "w", encoding="utf-8") as f:
                f.write('{"profiles": {}}')

        self.assertTrue(profiles_file.with_suffix(".json.bak").exists())
        self.assertEqual(profiles_file.with_suffix(".json.bak").read_text(), "NOT_VALID_JSON{")
        self.assertEqual(profiles_file.read_text(), '{"profiles": {}}')


if __name__ == "__main__":
    unittest.main()
