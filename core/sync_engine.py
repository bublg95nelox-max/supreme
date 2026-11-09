"""
Modpack synchronization engine for Lowuncher.
Compares local game files with remote manifest.json and performs robust, transactional sync.
"""
import os
import sys
import time
import json
import shutil
import logging
import hashlib
from pathlib import Path
from typing import Callable, Optional, Dict, Any, List, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed
try:
    import requests
except ImportError:
    requests = None
import urllib.request
import urllib.error
from urllib.parse import urlparse, unquote

logger = logging.getLogger("lowuncher.sync")


def compute_file_sha256(file_path: Path) -> str:
    """Computes SHA-256 hash of a file in 64KB chunks."""
    sha256 = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest().lower()


def safe_resolve_path(base_dir: Path, rel_path: str) -> Path:
    """
    Safely resolves a relative path against base_dir.
    Guards against path traversal (../), leading slashes, and absolute path injection.
    """
    if not rel_path or not isinstance(rel_path, str):
        raise ValueError(f"Недопустимый путь к файлу: {rel_path}")

    # Normalize backslashes to forward slashes and strip leading/trailing separators
    clean = rel_path.replace("\\", "/").strip()
    while clean.startswith("/"):
        clean = clean[1:]

    parts = clean.split("/")
    if ".." in parts:
        raise ValueError(f"Обнаружена попытка path traversal в пути: {rel_path}")

    base_resolved = base_dir.resolve()
    target_resolved = (base_resolved / clean).resolve()

    try:
        target_resolved.relative_to(base_resolved)
    except ValueError:
        raise ValueError(f"Путь выходит за пределы каталога игры: {rel_path}")

    return target_resolved


def check_free_space(target_dir: Path, required_bytes: int, safety_margin_mb: int = 150) -> None:
    """
    Verifies that the target filesystem has enough free space for the download
    plus a safety margin.
    """
    target_dir.mkdir(parents=True, exist_ok=True)
    usage = shutil.disk_usage(target_dir)
    safety_bytes = safety_margin_mb * 1024 * 1024
    if usage.free < (required_bytes + safety_bytes):
        free_mb = usage.free // (1024 * 1024)
        req_mb = (required_bytes + safety_bytes) // (1024 * 1024)
        raise IOError(
            f"Недостаточно свободного места на диске! Доступно: {free_mb} МБ, требуется минимум: {req_mb} МБ."
        )


def file_url_to_path(value: str) -> Path:
    """Safely converts file:// URL or local path to Path object, supporting Windows drive letters."""
    if not isinstance(value, str):
        return Path(value)
    parsed = urlparse(value)
    if parsed.scheme != "file":
        return Path(value)
    from urllib.request import url2pathname
    # If netloc is a Windows drive letter (e.g. C: in file://C:/path)
    if len(parsed.netloc) == 2 and parsed.netloc[1] == ":":
        return Path(url2pathname(unquote(f"{parsed.netloc}{parsed.path}")))
    if parsed.netloc and parsed.netloc not in ("", "localhost"):
        raise ValueError("UNC file:// URLs are not supported")
    return Path(url2pathname(unquote(parsed.path)))


def cleanup_temp_files(game_dir: Path) -> None:
    """
    Removes lingering *.download files or stale .sync_staging directories
    left behind by previous crashes or abrupt terminations.
    """
    staging_dir = game_dir / ".sync_staging"
    if staging_dir.exists():
        try:
            shutil.rmtree(staging_dir, ignore_errors=True)
            logger.info("Cleaned up stale .sync_staging directory")
        except Exception as e:
            logger.warning(f"Could not remove stale staging directory: {e}")

    if game_dir.exists():
        for dl_file in game_dir.rglob("*.download"):
            try:
                dl_file.unlink(missing_ok=True)
            except Exception:
                pass


class SyncNetworkError(RuntimeError):
    """Normalized network error for modpack synchronization."""
    def __init__(self, message: str, url: Optional[str] = None, status_code: Optional[int] = None, retryable: bool = False):
        super().__init__(message)
        self.url = url
        self.status_code = status_code
        self.retryable = retryable


class SyncEngine:
    """Synchronizes modpack files according to remote manifest with transactional safety."""

    def __init__(self, game_dir: Path):
        self.game_dir = game_dir
        self.headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Lowuncher/1.0"}
        self.session = None
        if requests is not None:
            try:
                from requests.adapters import HTTPAdapter
                self.session = requests.Session()
                self.session.headers.update(self.headers)
                adapter = HTTPAdapter(pool_connections=16, pool_maxsize=16)
                self.session.mount("https://", adapter)
                self.session.mount("http://", adapter)
            except Exception as e:
                logger.warning(f"Could not initialize pooled HTTP session: {e}")
                self.session = None

    def close(self):
        if self.session is not None:
            try:
                self.session.close()
            except Exception:
                pass
            self.session = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def fetch_manifest(self, manifest_url: str) -> Dict[str, Any]:
        """
        Fetches manifest.json. Supports HTTP/HTTPS URLs and local file paths.
        """
        if not isinstance(manifest_url, str) or not manifest_url.strip():
            raise ValueError("URL manifest.json не задан")
        manifest_url = manifest_url.strip()

        logger.info(f"Fetching manifest from {manifest_url}")

        # Support local file path or file:// URL
        if manifest_url.startswith("file://") or Path(manifest_url).is_file():
            file_path = file_url_to_path(manifest_url)
            with open(file_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
        else:
            parsed_manifest_url = urlparse(manifest_url)
            if parsed_manifest_url.scheme != "https" or not parsed_manifest_url.netloc:
                raise ValueError("manifest должен использовать HTTPS URL или локальный file:// URL")
            sep = "&" if "?" in manifest_url else "?"
            url_with_cb = f"{manifest_url}{sep}_cb={int(time.time())}"
            if self.session is not None:
                try:
                    resp = self.session.get(url_with_cb, timeout=(10, 15))
                    resp.raise_for_status()
                    manifest = resp.json()
                except requests.exceptions.HTTPError as exc:
                    status = exc.response.status_code if exc.response is not None else None
                    retryable = status in (408, 429) or (status is not None and status >= 500)
                    raise SyncNetworkError(
                        f"Сервер вернул ошибку ({status}) при загрузке manifest: {manifest_url}",
                        url=manifest_url,
                        status_code=status,
                        retryable=retryable,
                    ) from exc
                except requests.exceptions.RequestException as exc:
                    raise SyncNetworkError(
                        f"Не удалось подключиться к серверу для загрузки manifest: {exc}",
                        url=manifest_url,
                        retryable=True,
                    ) from exc
            elif requests is not None:
                try:
                    resp = requests.get(url_with_cb, headers=self.headers, timeout=(10, 15))
                    resp.raise_for_status()
                    manifest = resp.json()
                except requests.exceptions.HTTPError as exc:
                    status = exc.response.status_code if exc.response is not None else None
                    retryable = status in (408, 429) or (status is not None and status >= 500)
                    raise SyncNetworkError(
                        f"Сервер вернул ошибку ({status}) при загрузке manifest: {manifest_url}",
                        url=manifest_url,
                        status_code=status,
                        retryable=retryable,
                    ) from exc
                except requests.exceptions.RequestException as exc:
                    raise SyncNetworkError(
                        f"Не удалось подключиться к серверу для загрузки manifest: {exc}",
                        url=manifest_url,
                        retryable=True,
                    ) from exc
            else:
                try:
                    req = urllib.request.Request(url_with_cb, headers=self.headers)
                    with urllib.request.urlopen(req, timeout=15) as resp:
                        manifest = json.loads(resp.read().decode("utf-8"))
                except urllib.error.HTTPError as exc:
                    raise SyncNetworkError(
                        f"Сервер вернул ошибку ({exc.code}) при загрузке manifest: {manifest_url}",
                        url=manifest_url,
                        status_code=exc.code,
                        retryable=(exc.code >= 500),
                    ) from exc
                except (urllib.error.URLError, TimeoutError, OSError) as exc:
                    raise SyncNetworkError(
                        f"Ошибка соединения при загрузке manifest: {exc}",
                        url=manifest_url,
                        retryable=True,
                    ) from exc

        if not isinstance(manifest, dict) or "minecraft_version" not in manifest or "files" not in manifest:
            raise ValueError("Некорректная структура manifest.json: отсутствует minecraft_version или files")

        if not isinstance(manifest.get("files"), list):
            raise ValueError("Некорректный манифест: поле 'files' должно быть списком")

        # Cache successfully loaded manifest for offline resilience
        try:
            cache_file = self.game_dir / ".last_manifest.json"
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            with open(cache_file, "w", encoding="utf-8") as cf:
                json.dump(manifest, cf, indent=2, ensure_ascii=False)
        except Exception:
            pass

        return manifest

    def verify_local_installation(self, manifest: Dict[str, Any]) -> List[str]:
        """
        Verifies all files in manifest against existing local files.
        Returns a list of errors/mismatches. Empty list means installation is 100% verified.
        """
        invalid = []
        for file_entry in manifest.get("files", []):
            if not isinstance(file_entry, dict) or "path" not in file_entry:
                continue
            rel_p = file_entry["path"]
            try:
                target = safe_resolve_path(self.game_dir, rel_p)
            except ValueError:
                invalid.append(f"{rel_p}: unsafe path")
                continue

            if not target.is_file():
                invalid.append(f"{rel_p}: missing")
                continue

            expected_size = file_entry.get("size")
            if expected_size is not None and target.stat().st_size != expected_size:
                invalid.append(f"{rel_p}: size mismatch ({target.stat().st_size} != {expected_size})")
                continue

            expected_hash = (file_entry.get("sha256") or "").strip().lower()
            if expected_hash and len(expected_hash) == 64:
                actual_hash = compute_file_sha256(target)
                if actual_hash != expected_hash:
                    invalid.append(f"{rel_p}: sha256 mismatch")
        return invalid

    def plan_sync(self, manifest: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], List[Path]]:
        """
        Plans which files need to be downloaded and which obsolete mods need removal.
        Validates all paths for path traversal safety.
        Returns: (files_to_download, obsolete_files_to_delete)
        """
        files_to_download: List[Dict[str, Any]] = []
        manifest_relative_paths = set()

        for file_entry in manifest.get("files", []):
            if not isinstance(file_entry, dict):
                raise ValueError("Некорректная запись файла в manifest")
            raw_path = file_entry.get("path")
            if not raw_path:
                raise ValueError("Запись файла не содержит path")

            # Path traversal check
            local_path = safe_resolve_path(self.game_dir, raw_path)
            clean_rel = local_path.relative_to(self.game_dir.resolve()).as_posix()
            if clean_rel in manifest_relative_paths:
                raise ValueError(f"Дублирующийся путь в manifest: {clean_rel}")
            manifest_relative_paths.add(clean_rel)

            expected_hash = (file_entry.get("sha256") or "").strip().lower()
            if expected_hash and (len(expected_hash) != 64 or any(c not in "0123456789abcdef" for c in expected_hash)):
                raise ValueError(f"Некорректный SHA-256 для {clean_rel}")
            if not expected_hash and file_entry.get("url", "").startswith(("http://", "https://")):
                raise ValueError(f"Удалённый файл без SHA-256: {clean_rel}")

            if not local_path.exists():
                files_to_download.append(file_entry)
                continue

            # If overwrite is explicitly disabled (user config), keep existing local file
            if not file_entry.get("overwrite", True):
                continue

            # Compare checksums
            expected_hash = (file_entry.get("sha256") or "").strip().lower()
            if expected_hash and len(expected_hash) == 64:
                local_hash = compute_file_sha256(local_path)
                if local_hash != expected_hash:
                    logger.info(f"Hash mismatch for {clean_rel}: local={local_hash}, expected={expected_hash}")
                    files_to_download.append(file_entry)
            else:
                # Fallback to size check if hash is not provided
                expected_size = file_entry.get("size")
                if expected_size is not None and local_path.stat().st_size != expected_size:
                    logger.info(f"Size mismatch for {clean_rel}: local={local_path.stat().st_size}, expected={expected_size}")
                    files_to_download.append(file_entry)

        # Detect obsolete jars in mods/ (excluding temp files and staging)
        obsolete_files: List[Path] = []
        mods_dir = self.game_dir / "mods"
        if mods_dir.exists():
            for local_mod in mods_dir.rglob("*.jar"):
                if local_mod.name.endswith(".download") or ".sync_staging" in local_mod.as_posix():
                    continue
                try:
                    mod_rel = local_mod.relative_to(self.game_dir.resolve()).as_posix()
                    if mod_rel not in manifest_relative_paths:
                        obsolete_files.append(local_mod)
                except ValueError:
                    continue

        return files_to_download, obsolete_files

    def _download_file_to_staging(
        self,
        file_entry: Dict[str, Any],
        staging_dir: Path,
        progress_tracker: Optional[Callable[[int], None]] = None,
        max_retries: int = 3,
        session: Optional[Any] = None,
    ) -> Path:
        """
        Downloads a single file into staging_dir with retry logic and hash verification.
        Returns the path to the verified staged file.
        """
        url = file_entry.get("url")
        if not url:
            raise ValueError(f"Отсутствует URL для файла: {file_entry.get('path')}")
        parsed_url = urlparse(url)
        if parsed_url.scheme not in ("https", "file"):
            raise ValueError(f"Разрешены только HTTPS или локальный file:// URL: {url}")
        if parsed_url.scheme == "https" and not parsed_url.netloc:
            raise ValueError(f"Некорректный HTTPS URL: {url}")

        raw_path = file_entry["path"]
        # Determine destination in staging
        staged_target = safe_resolve_path(staging_dir, raw_path)
        staged_target.parent.mkdir(parents=True, exist_ok=True)
        temp_download = staged_target.with_suffix(f"{staged_target.suffix}.download")

        expected_hash = (file_entry.get("sha256") or "").strip().lower()
        if not expected_hash or len(expected_hash) != 64 or any(c not in "0123456789abcdef" for c in expected_hash):
            raise ValueError(f"Некорректный обязательный SHA-256 для {raw_path}")
        last_error = None

        for attempt in range(1, max_retries + 1):
            try:
                if temp_download.exists():
                    temp_download.unlink(missing_ok=True)

                downloaded = 0
                if url.startswith("file://"):
                    src_file = file_url_to_path(url)
                    if not src_file.exists():
                        raise FileNotFoundError(f"Локальный файл не найден: {src_file}")
                    with open(src_file, "rb") as sf, open(temp_download, "wb") as df:
                        while chunk := sf.read(65536):
                            df.write(chunk)
                            downloaded += len(chunk)
                            if progress_tracker:
                                progress_tracker(len(chunk))
                else:
                    if session is not None:
                        with session.get(url, stream=True, timeout=(15, 30)) as r:
                            r.raise_for_status()
                            with open(temp_download, "wb") as f:
                                for chunk in r.iter_content(chunk_size=65536):
                                    if chunk:
                                        f.write(chunk)
                                        downloaded += len(chunk)
                                        if progress_tracker:
                                            progress_tracker(len(chunk))
                    elif requests is not None:
                        with requests.get(url, stream=True, headers=self.headers, timeout=(15, 30)) as r:
                            r.raise_for_status()
                            with open(temp_download, "wb") as f:
                                for chunk in r.iter_content(chunk_size=65536):
                                    if chunk:
                                        f.write(chunk)
                                        downloaded += len(chunk)
                                        if progress_tracker:
                                            progress_tracker(len(chunk))
                    else:
                        req = urllib.request.Request(url, headers=self.headers)
                        with urllib.request.urlopen(req, timeout=30) as r:
                            with open(temp_download, "wb") as f:
                                while chunk := r.read(65536):
                                    f.write(chunk)
                                    downloaded += len(chunk)
                                    if progress_tracker:
                                        progress_tracker(len(chunk))

                # Verify SHA-256
                if expected_hash and len(expected_hash) == 64:
                    actual_hash = compute_file_sha256(temp_download)
                    if actual_hash != expected_hash:
                        raise IOError(
                            f"Несовпадение SHA-256 для {raw_path}! Ожидалось: {expected_hash}, получено: {actual_hash}"
                        )

                # Atomically promote temp_download to staged_target
                temp_download.replace(staged_target)
                logger.info(f"Staged and verified: {raw_path} (попытка {attempt})")
                return staged_target

            except Exception as e:
                last_error = e
                logger.warning(f"Ошибка загрузки {raw_path} (попытка {attempt}/{max_retries}): {e}")
                if temp_download.exists():
                    temp_download.unlink(missing_ok=True)
                if attempt < max_retries:
                    time.sleep(0.5 * attempt)

        status = None
        retryable = True
        if requests is not None and isinstance(last_error, requests.exceptions.HTTPError):
            status = last_error.response.status_code if last_error.response is not None else None
            retryable = status in (408, 429) or (status is not None and status >= 500)
        raise SyncNetworkError(
            f"Не удалось загрузить файл {raw_path} ({status or last_error}) после {max_retries} попыток",
            url=url,
            status_code=status,
            retryable=retryable,
        ) from last_error

    def sync(
        self,
        manifest_url: str,
        progress_callback: Optional[Callable[[int, int, str], None]] = None,
    ) -> Dict[str, Any]:
        """
        Executes full two-phase transactional sync:
        1. Cleanup lingering .download and staging files from past interruptions.
        2. Fetches and parses manifest.json.
        3. Checks required disk space against available filesystem space.
        4. Downloads all new/updated files to .sync_staging with retries and SHA-256 checks.
           -> If ANY download or verification fails: staging is wiped, and existing working installation remains untouched!
        5. Atomically commits staged files to game_dir.
        6. Safely cleans up obsolete files.
        """
        cleanup_temp_files(self.game_dir)

        if progress_callback:
            progress_callback(0, 100, "Проверка обновлений сборки...")

        manifest = self.fetch_manifest(manifest_url)
        to_download, obsolete = self.plan_sync(manifest)

        # If nothing to download, just clean obsolete mods (if any) and finish
        if not to_download:
            if obsolete:
                for obs_file in obsolete:
                    try:
                        logger.info(f"Removing obsolete mod: {obs_file.name}")
                        obs_file.unlink(missing_ok=True)
                    except Exception as e:
                        logger.warning(f"Could not delete obsolete file {obs_file}: {e}")
            logger.info("All modpack files are up to date.")
            if progress_callback:
                progress_callback(100, 100, "Сборка обновлена")
            return manifest

        # Check disk space
        total_bytes = sum(f.get("size", 1048576) for f in to_download)
        check_free_space(self.game_dir, total_bytes, safety_margin_mb=150)

        staging_dir = self.game_dir / ".sync_staging"
        staging_dir.mkdir(parents=True, exist_ok=True)

        session = self.session
        if session is None and requests is not None:
            try:
                from requests.adapters import HTTPAdapter
                self.session = requests.Session()
                self.session.headers.update(self.headers)
                adapter = HTTPAdapter(pool_connections=16, pool_maxsize=16)
                self.session.mount("https://", adapter)
                self.session.mount("http://", adapter)
                session = self.session
            except Exception as e:
                logger.warning(f"Could not initialize pooled HTTP session: {e}")
                session = None

        downloaded_bytes = 0
        total_files = len(to_download)
        completed_files = 0

        if progress_callback:
            progress_callback(0, max(1, total_bytes), f"Загрузка сборки (0/{total_files})...")

        def on_chunk(bytes_count: int):
            nonlocal downloaded_bytes
            downloaded_bytes += bytes_count
            if progress_callback:
                progress_callback(
                    downloaded_bytes,
                    max(downloaded_bytes, total_bytes),
                    f"Загрузка файлов ({completed_files}/{total_files})...",
                )

        # Phase 1: Download all files into staging directory
        staged_files: List[Tuple[Path, Path]] = []  # (staged_path, final_target_path)
        try:
            with ThreadPoolExecutor(max_workers=8) as pool:
                futures = {
                    pool.submit(self._download_file_to_staging, entry, staging_dir, on_chunk, 3, session): entry
                    for entry in to_download
                }
                for future in as_completed(futures):
                    entry = futures[future]
                    staged_path = future.result()
                    final_path = safe_resolve_path(self.game_dir, entry["path"])
                    staged_files.append((staged_path, final_path))
                    completed_files += 1
                    if progress_callback:
                        progress_callback(
                            downloaded_bytes,
                            max(downloaded_bytes, total_bytes),
                            f"Загрузка файлов ({completed_files}/{total_files})...",
                        )

            # Phase 2: Transactional Commit (All files verified!)
            if progress_callback:
                progress_callback(total_bytes, total_bytes, "Применение обновлений сборки...")

            committed_backups: List[Tuple[Path, Path]] = []
            try:
                for staged_path, final_path in staged_files:
                    final_path.parent.mkdir(parents=True, exist_ok=True)
                    backup_path = staged_path.with_suffix(".backup")
                    if final_path.exists():
                        try:
                            final_path.replace(backup_path)
                            committed_backups.append((backup_path, final_path))
                        except Exception:
                            pass

                    # On Windows, antivirus or background indexing can briefly lock freshly downloaded files
                    for attempt in range(5):
                        try:
                            if sys.platform == "win32" and final_path.exists():
                                try:
                                    final_path.unlink(missing_ok=True)
                                except Exception:
                                    pass
                            staged_path.replace(final_path)
                            break
                        except (PermissionError, OSError) as pe:
                            if attempt == 4:
                                logger.error(f"Failed to commit staged file {final_path} after 5 attempts: {pe}")
                                raise
                            time.sleep(0.2)
            except Exception as commit_err:
                logger.error(f"Commit interrupted: {commit_err}. Rolling back committed files...")
                for backup_path, final_path in committed_backups:
                    try:
                        if backup_path.exists():
                            backup_path.replace(final_path)
                    except Exception:
                        pass
                raise

            # Now that all new files are successfully installed, remove obsolete files
            for obs_file in obsolete:
                try:
                    logger.info(f"Removing obsolete mod: {obs_file.name}")
                    obs_file.unlink(missing_ok=True)
                except Exception as e:
                    logger.warning(f"Could not delete obsolete file {obs_file}: {e}")

            if progress_callback:
                progress_callback(total_bytes, total_bytes, "Сборка успешно обновлена")

            return manifest

        except Exception as e:
            logger.error(f"Sync failed, existing installation preserved: {e}")
            raise
        finally:
            if session:
                try:
                    session.close()
                except Exception:
                    pass
            # Always clean up staging directory
            if staging_dir.exists():
                shutil.rmtree(staging_dir, ignore_errors=True)

    sync_from_url = sync
