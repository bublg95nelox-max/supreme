"""
Minecraft and NeoForge installer module.
Handles downloading vanilla Minecraft client and executing NeoForge installer.
"""
import os
import sys
import json
import shutil
import tempfile
import logging
import subprocess
from pathlib import Path
from typing import Callable, Optional, Dict, Any

# Ensure bundled libraries in libraries/python are available
_root_dir = Path(__file__).resolve().parent.parent
_bundled_libs = _root_dir / "libraries" / "python"
if _bundled_libs.is_dir() and str(_bundled_libs) not in sys.path:
    sys.path.insert(0, str(_bundled_libs))

import requests
import minecraft_launcher_lib as mll

logger = logging.getLogger("lowuncher.installer")


class Installer:
    """Manages installation of Minecraft and NeoForge."""

    def __init__(self, game_dir: Path):
        self.game_dir = game_dir
        self.versions_dir = self.game_dir / "versions"
        self.versions_dir.mkdir(parents=True, exist_ok=True)

    def is_version_installed(self, version_id: str) -> bool:
        """Checks if a specific version directory and json exist."""
        version_json = self.versions_dir / version_id / f"{version_id}.json"
        return version_json.exists() and version_json.stat().st_size > 0

    def install_vanilla(
        self,
        version: str,
        progress_callback: Optional[Callable[[int, int, str], None]] = None,
    ) -> None:
        """
        Installs vanilla Minecraft version via minecraft-launcher-lib.
        """
        if self.is_version_installed(version):
            logger.info(f"Vanilla Minecraft {version} is already installed.")
            return

        # Check for pre-existing vanilla version jar/json from game or common directories
        candidate_jars = [
            self.game_dir.parent.parent / "game" / "versions" / version / f"{version}.jar",
            self.game_dir.parent.parent / "common" / "versions" / version / f"{version}.jar",
            Path.home() / ".local" / "share" / "PrismLauncher" / "libraries" / "com" / "mojang" / "minecraft" / version / f"minecraft-{version}-client.jar",
        ]
        target_version_dir = self.game_dir / "versions" / version
        target_version_dir.mkdir(parents=True, exist_ok=True)
        target_jar = target_version_dir / f"{version}.jar"

        for c_jar in candidate_jars:
            if c_jar.is_file() and c_jar.stat().st_size > 20000000:
                logger.info(f"Reusing cached vanilla client jar: {c_jar}")
                shutil.copy2(c_jar, target_jar)
                c_json = c_jar.parent / f"{version}.json"
                if c_json.is_file():
                    shutil.copy2(c_json, target_version_dir / f"{version}.json")
                break

        if self.is_version_installed(version):
            logger.info(f"Vanilla Minecraft {version} cached files successfully reused.")
            return

        logger.info(f"Installing vanilla Minecraft {version} into {self.game_dir}")

        current_max = 100
        current_progress = 0
        current_status = f"Установка Minecraft {version}..."

        def set_status(status: str):
            nonlocal current_status
            current_status = status
            if progress_callback:
                progress_callback(current_progress, current_max, current_status)

        def set_progress(progress: int):
            nonlocal current_progress
            current_progress = progress
            if progress_callback:
                progress_callback(current_progress, current_max, current_status)

        def set_max(max_val: int):
            nonlocal current_max
            current_max = max(1, max_val)
            if progress_callback:
                progress_callback(current_progress, current_max, current_status)

        callback_dict = {
            "setStatus": set_status,
            "setProgress": set_progress,
            "setMax": set_max,
        }

        mll.install.install_minecraft_version(
            version=version,
            minecraft_directory=str(self.game_dir),
            callback=callback_dict,
        )
        logger.info(f"Vanilla Minecraft {version} installed successfully.")

    def install_neoforge(
        self,
        minecraft_version: str,
        neoforge_version: str,
        java_path: Path,
        installer_url: Optional[str] = None,
        progress_callback: Optional[Callable[[int, int, str], None]] = None,
    ) -> str:
        """
        Installs NeoForge for the given Minecraft version.
        Returns the installed version ID (e.g. 'neoforge-21.1.72').
        """
        installed_version_id = f"neoforge-{neoforge_version}"
        if self.is_version_installed(installed_version_id):
            logger.info(f"NeoForge {neoforge_version} is already installed.")
            return installed_version_id

        # 1. Ensure vanilla is installed first
        if not self.is_version_installed(minecraft_version):
            if progress_callback:
                progress_callback(0, 100, f"Установка базового Minecraft {minecraft_version}...")
            self.install_vanilla(minecraft_version, progress_callback)

        # 2. Check for pre-existing local installer jar (in instance neoforge directory)
        local_installer = self.game_dir / "neoforge" / f"neoforge-{neoforge_version}-installer.jar"
        if not local_installer.is_file():
            neoforge_dir = self.game_dir / "neoforge"
            if neoforge_dir.is_dir():
                for f in neoforge_dir.glob("*.jar"):
                    if "installer" in f.name.lower():
                        local_installer = f
                        break

        # 3. Determine installer download URLs with robust mirrors (including GitHub bypass)
        candidate_urls = []
        if installer_url:
            candidate_urls.append(installer_url)

        mirror_base = os.environ.get("LOWUNCHER_NEOFORGE_MIRROR")
        candidate_urls.extend([
            f"https://raw.githubusercontent.com/bublg95nelox-max/supreme/main/neoforge/neoforge-{neoforge_version}-installer.jar",
            *( [mirror_base.rstrip("/") + f"/{neoforge_version}/neoforge-{neoforge_version}-installer.jar"] if mirror_base else [] ),
            f"https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/{neoforge_version}/neoforge-{neoforge_version}-installer.jar",
            f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{neoforge_version}/neoforge-{neoforge_version}-installer.jar",
        ])

        if progress_callback:
            progress_callback(0, 100, "Подготовка установщика NeoForge...")

        with tempfile.TemporaryDirectory(prefix="lowuncher-neoforge-") as tempdir:
            temp_installer = Path(tempdir) / "neoforge-installer.jar"
            download_success = False
            last_error = None

            if local_installer.is_file() and local_installer.stat().st_size > 500000:
                logger.info(f"Using local pre-bundled NeoForge installer: {local_installer} ({local_installer.stat().st_size} bytes)")
                shutil.copy2(local_installer, temp_installer)
                download_success = True

            if not download_success:
                headers = {
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }

                for url in candidate_urls:
                    try:
                        if not url.startswith("https://"):
                            raise ValueError(f"NeoForge URL must use HTTPS: {url}")
                        logger.info(f"Attempting NeoForge installer download from: {url}")
                        temp_installer.unlink(missing_ok=True)
                        with requests.get(url, stream=True, headers=headers, timeout=(5, 12), allow_redirects=True) as r:
                            if r.status_code == 200:
                                total_size = int(r.headers.get("content-length", 6800000))
                                downloaded = 0
                                with open(temp_installer, "wb") as f:
                                    for chunk in r.iter_content(chunk_size=1024 * 64):
                                        if chunk:
                                            f.write(chunk)
                                            downloaded += len(chunk)
                                            if progress_callback and total_size > 0:
                                                progress_callback(downloaded, total_size, "Скачивание NeoForge...")
                                if temp_installer.stat().st_size > 500000:
                                    download_success = True
                                    logger.info(f"Successfully downloaded NeoForge installer ({temp_installer.stat().st_size} bytes)")
                                    break
                    except Exception as e:
                        temp_installer.unlink(missing_ok=True)
                        logger.warning(f"Download mirror failed ({url}): {e}")
                        last_error = e

            if not download_success:
                raise RuntimeError(f"Не удалось скачать NeoForge ни с одного зеркала: {last_error}")

            # 3. Create launcher profiles if missing or invalid (required by NeoForge installer)
            profiles_json = self.game_dir / "launcher_profiles.json"
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
                with open(profiles_json, "w", encoding="utf-8") as f:
                    f.write('{"profiles": {}}')

            # 4. Run installer headlessly
            if progress_callback:
                progress_callback(50, 100, "Применение патчей NeoForge (может занять 1-2 мин)...")

            startupinfo = None
            if sys.platform == "win32":
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW

            cmd = [
                str(java_path),
                "-jar",
                str(temp_installer),
                "--install-client",
                str(self.game_dir),
            ]
            logger.info(f"Running NeoForge installer: {' '.join(cmd)}")

            result = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                cwd=tempdir,
                startupinfo=startupinfo,
            )

            if result.returncode != 0:
                logger.error(f"NeoForge installer failed: {result.stderr or result.stdout}")
                raise RuntimeError(f"NeoForge installation failed: {result.stderr or result.stdout}")

        if not self.is_version_installed(installed_version_id):
            # Check if directory name contains the version and has valid json
            candidates = [d.name for d in self.versions_dir.iterdir() if d.is_dir() and neoforge_version in d.name]
            if not candidates:
                candidates = [d.name for d in self.versions_dir.iterdir() if d.is_dir() and "neoforge" in d.name.lower()]
            found_candidate = None
            for cand in candidates:
                if self.is_version_installed(cand):
                    found_candidate = cand
                    break
            if found_candidate:
                installed_version_id = found_candidate
            else:
                raise RuntimeError(f"NeoForge installed version '{installed_version_id}' not found in {self.versions_dir}")

        logger.info(f"NeoForge installed successfully as: {installed_version_id}")
        return installed_version_id
