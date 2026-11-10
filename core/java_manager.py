"""
Java 21 runtime management for Lowuncher.
Downloads portable Adoptium Eclipse Temurin JRE 21 and isolates it in runtime/.
"""
import os
import sys
import shutil
import tarfile
import zipfile
import logging
import platform
import subprocess
import re
from pathlib import Path
from typing import Callable, Optional, Tuple

# Ensure bundled libraries in libraries/python are available
_root_dir = Path(__file__).resolve().parent.parent
_bundled_libs = _root_dir / "libraries" / "python"
if _bundled_libs.is_dir() and str(_bundled_libs) not in sys.path:
    sys.path.insert(0, str(_bundled_libs))

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

logger = logging.getLogger("lowuncher.java")


class JavaManager:
    """Manages portable Java 21 installation."""

    ADOPTIUM_API_BASE = os.environ.get(
        "LOWUNCHER_JAVA_API",
        "https://api.adoptium.net/v3/assets/feature_releases/21/ga",
    )
    JAVA_API_FALLBACKS = (
        "https://api.adoptium.net/v3/assets/feature_releases/21/ga",
        "https://api.adoptium.net/v3/assets/latest/21/hotspot",
    )

    def __init__(self, runtime_dir: Path):
        self.runtime_dir = runtime_dir
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.java21_dir = self.runtime_dir / "java-21"
        self.session = requests.Session()
        retry = Retry(
            total=2,
            connect=2,
            read=2,
            backoff_factor=0.4,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=frozenset({"GET"}),
        )
        self.session.mount("https://", HTTPAdapter(max_retries=retry))

    def get_java_executable(self) -> Optional[Path]:
        """
        Returns the path to the Java executable if installed and valid.
        On Windows, prefers javaw.exe (no console window), falls back to java.exe.
        On Unix, returns bin/java.
        Searches isolated runtime directory, user runtime directories, PrismLauncher runtimes,
        and standard system JDK directories.
        """
        candidates = []
        search_dirs = [self.java21_dir]

        # Additional potential runtime directories on this system
        home = Path.home()
        extra_dirs = [
            self.runtime_dir / "java-21",
            self.runtime_dir.parent / "runtime" / "java-21",
            home / ".lowuncher" / "runtime" / "java-21",
            home / ".lowuncher" / "game" / "runtime" / "java-runtime-delta" / "linux" / "java-runtime-delta",
            home / ".local" / "share" / "PrismLauncher" / "java" / "java-runtime-delta",
            home / ".jdks",
            Path("/usr/lib/jvm"),
            Path("/usr/lib64/jvm"),
        ]
        for ed in extra_dirs:
            if ed.is_dir() and ed not in search_dirs:
                search_dirs.append(ed)

        for sdir in search_dirs:
            if not sdir.exists():
                continue
            if sys.platform == "win32":
                candidates.extend(sdir.glob("**/bin/javaw.exe"))
                candidates.extend(sdir.glob("**/bin/java.exe"))
            else:
                candidates.extend(sdir.glob("**/bin/java"))

        for candidate in candidates:
            if candidate.is_file():
                if sys.platform != "win32":
                    try:
                        candidate.chmod(candidate.stat().st_mode | 0o755)
                    except Exception:
                        pass
                if self.verify_java(candidate):
                    logger.info(f"Discovered valid Java 21+ at: {candidate}")
                    return candidate.resolve()
        return None

    def verify_java(self, java_path: Path) -> bool:
        """Verifies that the java executable exists and is version 21+."""
        try:
            cmd = [str(java_path), "-version"]
            # javaw on Windows might not pipe output, so java.exe is better for verification
            if sys.platform == "win32" and "javaw.exe" in java_path.name.lower():
                verify_exe = java_path.parent / "java.exe"
                if verify_exe.exists():
                    cmd = [str(verify_exe), "-version"]

            result = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=5,
            )
            output = result.stderr or result.stdout
            first_line = output.strip().splitlines()[0] if output else ""
            logger.info(f"Java version output: {first_line or 'empty'}")
            match = re.search(r'(?:version\s+)?["\']?(\d+)(?:\.\d+)?', first_line)
            return result.returncode == 0 and bool(match) and int(match.group(1)) >= 21
        except Exception as e:
            logger.warning(f"Java verification failed for {java_path}: {e}")
            return False

    def is_ready(self) -> bool:
        """Returns True if Java 21 is already installed and working."""
        exe = self.get_java_executable()
        if not exe:
            return False
        return self.verify_java(exe)

    def get_system_java(self) -> Optional[Path]:
        """
        Detects existing valid Java 21+ installation on the host system:
        Checks JAVA_HOME and system PATH (javaw / java).
        """
        candidates = []
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            jh_path = Path(java_home)
            if sys.platform == "win32":
                candidates.append(jh_path / "bin" / "javaw.exe")
                candidates.append(jh_path / "bin" / "java.exe")
            else:
                candidates.append(jh_path / "bin" / "java")

        which_names = ["javaw", "java"] if sys.platform == "win32" else ["java"]
        for name in which_names:
            found = shutil.which(name)
            if found:
                candidates.append(Path(found))

        for cand in candidates:
            if cand.is_file() and self.verify_java(cand):
                logger.info(f"Found valid system Java 21+: {cand}")
                return cand.resolve()
        return None

    def ensure_java_21(
        self,
        progress_callback: Optional[Callable[[int, int, str], None]] = None,
        prefer_system: bool = False,
    ) -> Path:
        """
        Ensures Java 21 is available, checking existing local/system runtimes first,
        and downloading Adoptium only if no valid Java 21 is found.
        """
        # Always check existing local runtimes first
        local_exe = self.get_java_executable()
        if local_exe:
            logger.info(f"Using discovered Java 21: {local_exe}")
            return local_exe

        # Check system PATH / JAVA_HOME
        sys_java = self.get_system_java()
        if sys_java:
            logger.info(f"Using system Java 21+: {sys_java}")
            return sys_java

        try:
            return self.install(progress_callback=progress_callback)
        except Exception as e:
            logger.warning(f"Failed to install portable Adoptium Java 21: {e}")
            sys_java = self.get_system_java()
            if sys_java:
                logger.info(f"Falling back to host system Java 21: {sys_java}")
                return sys_java
            raise

    def _get_system_info(self) -> Tuple[str, str]:
        """Maps sys.platform and platform.machine() to Adoptium parameters."""
        os_name = "windows" if sys.platform == "win32" else ("mac" if sys.platform == "darwin" else "linux")
        machine = platform.machine().lower()

        if machine in ("x86_64", "amd64"):
            arch = "x64"
        elif machine in ("arm64", "aarch64"):
            arch = "aarch64"
        else:
            arch = "x64"

        return os_name, arch

    def get_download_url(self) -> Tuple[str, str, int]:
        """
        Fetches the download URL, filename, and file size for Adoptium JRE 21.
        Returns (url, filename, size_bytes).
        """
        os_name, arch = self._get_system_info()
        headers = {"User-Agent": "Lowuncher/1.0 (Java bootstrap)"}
        errors = []
        bases = tuple(dict.fromkeys((self.ADOPTIUM_API_BASE, *self.JAVA_API_FALLBACKS)))

        for base in bases:
            url = f"{base}?image_type=jre&os={os_name}&architecture={arch}"
            try:
                resp = self.session.get(url, headers=headers, timeout=(5, 15))
                resp.raise_for_status()
                data = resp.json()
                if data and data[0].get("binaries"):
                    pkg = data[0]["binaries"][0]["package"]
                    link = pkg.get("link")
                    if link and link.startswith("https://"):
                        return link, pkg["name"], pkg.get("size", 0)
                errors.append(f"{base}: empty release response")
            except Exception as exc:
                errors.append(f"{base}: {exc}")
                logger.warning("Java metadata endpoint failed: %s", base)

        raise RuntimeError(f"No Adoptium releases found for {os_name}/{arch}: {'; '.join(errors)}")

    def install(self, progress_callback: Optional[Callable[[int, int, str], None]] = None) -> Path:
        """
        Downloads and installs Adoptium Java 21 into runtime/java-21.
        Returns the path to the executable.
        """
        if self.is_ready():
            exe = self.get_java_executable()
            if not exe:
                raise RuntimeError("Исполняемый файл Java 21 не найден, хотя рантайм числится готовым.")
            return exe

        if progress_callback:
            progress_callback(0, 100, "Поиск Java 21...")

        download_url, filename, expected_size = self.get_download_url()
        temp_archive = self.runtime_dir / f"{filename}.part"
        final_archive = self.runtime_dir / filename
        temp_archive.unlink(missing_ok=True)

        logger.info(f"Downloading Java 21 from {download_url} -> {temp_archive}")
        if progress_callback:
            progress_callback(0, expected_size or 100, "Скачивание Java 21...")

        # Download with streaming and progress into .part file
        try:
            with self.session.get(download_url, stream=True, timeout=(10, 60), headers={"User-Agent": "Lowuncher/1.0"}) as r:
                r.raise_for_status()
                total_size = int(r.headers.get("content-length", expected_size or 0))
                downloaded = 0
                with open(temp_archive, "wb") as f:
                    for chunk in r.iter_content(chunk_size=1024 * 64):
                        if chunk:
                            f.write(chunk)
                            downloaded += len(chunk)
                            if progress_callback and total_size > 0:
                                progress_callback(downloaded, total_size, "Скачивание Java 21...")

            if total_size > 0 and temp_archive.stat().st_size != total_size:
                raise IOError(f"Размер скачанного архива Java ({temp_archive.stat().st_size}) не совпадает с Content-Length ({total_size})")

            temp_archive.replace(final_archive)
        except Exception:
            temp_archive.unlink(missing_ok=True)
            if final_archive.exists():
                final_archive.unlink(missing_ok=True)
            raise

        # Clean old directory if corrupted
        if self.java21_dir.exists():
            shutil.rmtree(self.java21_dir, ignore_errors=True)
        self.java21_dir.mkdir(parents=True, exist_ok=True)

        if progress_callback:
            progress_callback(100, 100, "Распаковка Java 21...")

        logger.info(f"Extracting {final_archive} to {self.java21_dir}")
        try:
            root = self.java21_dir.resolve()
            if filename.endswith(".zip"):
                with zipfile.ZipFile(final_archive, "r") as zf:
                    for member in zf.infolist():
                        clean_name = member.filename.replace("\\", "/").strip()
                        if clean_name.startswith("/") or ":" in clean_name:
                            raise ValueError(f"Unsafe file path in ZIP: {member.filename}")
                        target = (self.java21_dir / member.filename).resolve()
                        try:
                            target.relative_to(root)
                        except ValueError as exc:
                            raise ValueError(f"Path traversal detected in ZIP: {member.filename}") from exc
                    zf.extractall(self.java21_dir)
            elif filename.endswith(".tar.gz") or filename.endswith(".tgz"):
                with tarfile.open(final_archive, "r:gz") as tf:
                    for member in tf.getmembers():
                        target = (self.java21_dir / member.name).resolve()
                        if not str(target).startswith(str(root)):
                            raise ValueError(f"Path traversal detected in TAR: {member.name}")
                        if member.issym() or member.islnk():
                            if sys.platform == "win32" and member.islnk():
                                raise ValueError(f"Hard links in TAR archives are not supported on Windows: {member.name}")
                            link_target = (target.parent / member.linkname).resolve()
                            if not str(link_target).startswith(str(root)):
                                raise ValueError(f"Unsafe symlink target: {member.linkname}")
                    tf.extractall(self.java21_dir)
            else:
                raise ValueError(f"Unknown archive format: {filename}")

            if sys.platform != "win32":
                for bin_file in self.java21_dir.glob("**/bin/*"):
                    try:
                        bin_file.chmod(bin_file.stat().st_mode | 0o755)
                    except Exception:
                        pass
        except ValueError as exc:
            raise RuntimeError(f"Unsafe Java archive: {exc}") from exc
        finally:
            temp_archive.unlink(missing_ok=True)
            if final_archive.exists():
                final_archive.unlink(missing_ok=True)

        exe = self.get_java_executable()
        if not exe:
            raise RuntimeError("Java executable not found after extraction!")

        if not self.verify_java(exe):
            raise RuntimeError(f"Installed Java at {exe} failed verification check!")

        logger.info(f"Java 21 successfully installed: {exe}")
        return exe
