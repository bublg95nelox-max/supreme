"""
Bridge CLI for Lowuncher.
Acts as the communication bridge between HeliosLauncher (Node.js/Electron)
and the Python Minecraft launch engine (minecraft-launcher-lib, Java 21, NeoForge, SyncEngine).
"""
from __future__ import annotations

import sys
import os

# Force UTF-8 encoding on standard streams to eliminate question mark replacement characters
for stream in (sys.stdout, sys.stderr, sys.stdin):
    try:
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"
os.environ["PYTHONLEGACYWINDOWSSTDIO"] = "0"

import json
import time
import hashlib
import argparse
import logging
import signal
from pathlib import Path
from typing import Optional, Callable, Dict, Any, List

# Ensure lowuncher root and bundled libraries are on sys.path FIRST
_root_dir = Path(__file__).resolve().parent.parent
if str(_root_dir) not in sys.path:
    sys.path.insert(0, str(_root_dir))

for candidate_libs in [
    _root_dir / "libraries" / "python",
    Path(__file__).resolve().parent.parent.parent / "libraries" / "python",
    Path(sys.executable).parent.parent / "libraries" / "python",
    _root_dir / "build" / "win-python" / "Lib" / "site-packages",
]:
    if candidate_libs.is_dir() and str(candidate_libs) not in sys.path:
        sys.path.insert(0, str(candidate_libs))

try:
    import requests
except ImportError:
    requests = None

import urllib.request
import urllib.error

from core.java_manager import JavaManager
from core.installer import Installer
from core.sync_engine import SyncEngine, SyncNetworkError
from core.launcher import GameLauncher
from core.auth import AuthManager

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("lowuncher.bridge")

DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/bublg95nelox-max/supreme/main/manifest.json"
DEFAULT_MODPACK_URL = "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.5/modpack-supreme-1.21.1.zip"
FALLBACK_MODPACK_URLS = [
    "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.5/modpack-supreme-1.21.1.zip",
    "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.6/modpack-supreme-1.21.1.zip",
    "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.7/modpack-supreme-1.21.1.zip",
]
MODPACK_VERSION = "1.0.7"


def emit_event(event_type: str, data: dict):
    """Outputs a JSON event line to stdout for Electron IPC reading."""
    payload = {"type": event_type, **data}
    print(f"[BRIDGE_EVENT] {json.dumps(payload, ensure_ascii=False)}", flush=True)


def ensure_servers_dat(game_dir: Path):
    """Pre-populates servers.dat in the instance game directory with official servers."""
    try:
        servers_file = game_dir / "servers.dat"
        import struct
        import gzip

        def enc_str(s: str) -> bytes:
            raw = s.encode("utf-8")
            return struct.pack(">H", len(raw)) + raw

        should_write = not servers_file.exists()
        if servers_file.exists():
            try:
                raw_decomp = gzip.decompress(servers_file.read_bytes())
                if b"89.248.236.145" not in raw_decomp:
                    should_write = True
            except Exception:
                should_write = True

        if should_write:
            servers = [
                {"name": "Supreme (Основной)", "ip": "89.248.236.145:27160"},
                {"name": "Supreme (Резерв)", "ip": "ozdangalla.netmc.ru"},
            ]
            payload = b"\x0a\x00\x00\x09" + enc_str("servers") + b"\x0a" + struct.pack(">i", len(servers))
            for s in servers:
                payload += b"\x08" + enc_str("name") + enc_str(s["name"])
                payload += b"\x08" + enc_str("ip") + enc_str(s["ip"])
                payload += b"\x00"
            payload += b"\x00"

            with open(servers_file, "wb") as f:
                f.write(gzip.compress(payload))
            logger.info(f"Pre-populated servers.dat in {servers_file}")
    except Exception as e:
        logger.warning(f"Could not ensure servers.dat: {e}")


def purge_blacklisted_mods(game_dir: Path):
    """
    Purges obsolete or removed mods and their configs.
    Ensures players who downloaded earlier builds get FTB Chunks, Ultimine, Backups 2,
    Essentials, and FTBChecker removed automatically.
    """
    mods_dir = game_dir / "mods"
    if mods_dir.is_dir():
        BLACKLIST_JARS = [
            "*ftb*chunks*",
            "*ftb*ultimine*",
            "*ftb*backup*",
            "*ftb*essential*",
            "*ftb*checker*",
        ]
        for pattern in BLACKLIST_JARS:
            for stale in mods_dir.glob(f"{pattern}.jar"):
                try:
                    stale.unlink()
                    logger.info(f"Purged blacklisted mod jar: {stale.name}")
                except Exception as ex:
                    logger.warning(f"Could not delete {stale.name}: {ex}")

    # Also clean up obsolete FTB config files
    for conf_folder in [game_dir / "config", game_dir / "defaultconfigs"]:
        if conf_folder.is_dir():
            for conf_pat in ["ftbchunks*", "ftbultimine*", "ftbbackups2*", "ftbessentials*", "ftbchecker*"]:
                for stale_conf in conf_folder.glob(conf_pat):
                    try:
                        stale_conf.unlink()
                        logger.info(f"Purged obsolete config: {stale_conf.name}")
                    except Exception as ex:
                        logger.warning(f"Could not delete config {stale_conf.name}: {ex}")


def safe_extract_zip(
    zf,
    destination: Path,
    progress_callback: Optional[Callable[[int, int, str], None]] = None,
    msg_prefix: str = "Распаковка",
) -> None:
    """
    Safely extracts a ZIP archive guarding against Path Traversal (Zip Slip).
    Reports progress for extracted files to prevent UI freeze.
    """
    root = destination.resolve()
    members = [info for info in zf.infolist() if not info.is_dir() and not info.filename.startswith(("/", "\\"))]
    total_files = max(1, len(members))

    for idx, info in enumerate(members, 1):
        target = (destination / info.filename).resolve()
        try:
            target.relative_to(root)
        except ValueError as exc:
            raise RuntimeError(f"Небезопасный путь в архиве (Zip Slip): {info.filename}") from exc

        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info, "r") as source, target.open("wb") as output:
            shutil.copyfileobj(source, output)

        if progress_callback and (idx % 4 == 0 or idx == total_files):
            pct = int((idx / total_files) * 100)
            progress_callback(idx, total_files, f"{msg_prefix} ({idx}/{total_files})... {pct}%")


def ensure_modpack(
    game_dir: Path,
    modpack_url: Optional[str] = None,
    progress_callback: Optional[Callable[[int, int, str], None]] = None,
):
    """
    Ensures that the Supreme modpack is fully installed in game_dir.
    Verifies that EVERY required file/mod is present. If missing/corrupt, restores transactionally.
    """
    import zipfile
    mods_dir = game_dir / "mods"
    marker_file = game_dir / ".modpack_installed.json"

    # Always purge blacklisted mods first
    purge_blacklisted_mods(game_dir)

    installed_ver = None
    if marker_file.is_file():
        try:
            with open(marker_file, "r", encoding="utf-8") as f:
                installed_ver = json.load(f).get("version")
        except Exception:
            installed_ver = None

    # 1. If manifest cache is available, verify all files by size and SHA-256
    manifest_cache = game_dir / ".last_manifest.json"
    if manifest_cache.is_file() and installed_ver == MODPACK_VERSION:
        try:
            with open(manifest_cache, "r", encoding="utf-8") as mf:
                m_data = json.load(mf)
            sync_eng = SyncEngine(game_dir)
            invalid = sync_eng.verify_local_installation(m_data)
            if not invalid:
                mod_count = len(list(mods_dir.glob("*.jar"))) if mods_dir.is_dir() else 0
                logger.info(f"Modpack {MODPACK_VERSION} verified: all files intact ({mod_count} mods).")
                if progress_callback:
                    progress_callback(100, 100, f"Сборка модов готова ({mod_count} модов)")
                return
            logger.warning(f"Local installation has invalid/missing files: {invalid[:5]}")
        except Exception as ve:
            logger.warning(f"Could not verify install via manifest cache: {ve}")

    # Check local pre-bundled modpack archive candidates
    local_candidates = [
        _root_dir / "libraries" / "modpack-supreme-1.21.1.zip",
        _root_dir / "dist" / "modpack-supreme-1.21.1.zip",
        Path(__file__).resolve().parent.parent / "libraries" / "modpack-supreme-1.21.1.zip",
        Path(sys.executable).parent.parent / "libraries" / "modpack-supreme-1.21.1.zip",
    ]
    local_zip = None
    for candidate in local_candidates:
        if candidate.is_file():
            local_zip = candidate
            break

    # If local zip is available, extract to staging and commit atomically
    if local_zip:
        staging_dir = game_dir / ".modpack_staging"
        shutil.rmtree(staging_dir, ignore_errors=True)
        staging_dir.mkdir(parents=True, exist_ok=True)
        try:
            with zipfile.ZipFile(local_zip, "r") as zf:
                logger.info(f"Extracting local modpack {local_zip.name} to staging...")
                safe_extract_zip(zf, staging_dir, progress_callback, "Распаковка модпака")

            # Move staged files into game_dir
            for staged_f in staging_dir.rglob("*"):
                if staged_f.is_file():
                    rel_p = staged_f.relative_to(staging_dir)
                    dest_f = game_dir / rel_p
                    dest_f.parent.mkdir(parents=True, exist_ok=True)
                    staged_f.replace(dest_f)

            purge_blacklisted_mods(game_dir)
            mod_count = len(list((game_dir / "mods").glob("*.jar")))

            # Atomic write of marker file
            marker_tmp = marker_file.with_suffix(".tmp")
            with open(marker_tmp, "w", encoding="utf-8") as f:
                json.dump({
                    "version": MODPACK_VERSION,
                    "installed_at": time.time(),
                    "mods_count": mod_count
                }, f, indent=2, ensure_ascii=False)
            marker_tmp.replace(marker_file)

            if progress_callback:
                progress_callback(100, 100, f"Сборка модов готова ({mod_count} модов)")
            return
        except Exception as e:
            logger.warning(f"Could not verify/extract from local zip {local_zip}: {e}")
        finally:
            shutil.rmtree(staging_dir, ignore_errors=True)

    # Fallback: Download from remote release URL
    target_candidates = []
    if modpack_url:
        target_candidates.append(modpack_url)
    for u in FALLBACK_MODPACK_URLS:
        if u not in target_candidates:
            target_candidates.append(u)

    tmp_zip = game_dir / ".modpack_download.tmp"
    download_success = False
    last_dl_error = None

    for target_url in target_candidates:
        logger.info(f"Downloading modpack from {target_url}...")
        tmp_zip.unlink(missing_ok=True)
        try:
            req = urllib.request.Request(
                target_url,
                headers={"User-Agent": "Lowuncher/1.0.7"}
            )
            with urllib.request.urlopen(req, timeout=60) as resp:
                total_bytes = int(resp.headers.get("Content-Length", 0))
                downloaded = 0
                last_report = 0

                with open(tmp_zip, "wb") as f:
                    while True:
                        chunk = resp.read(131072)
                        if not chunk:
                            break
                        f.write(chunk)
                        downloaded += len(chunk)
                        now = time.time()
                        if now - last_report > 0.3 or (total_bytes > 0 and downloaded == total_bytes):
                            last_report = now
                            if total_bytes > 0:
                                pct = int((downloaded / total_bytes) * 100)
                                dl_mb = downloaded / 1048576
                                tot_mb = total_bytes / 1048576
                                msg = f"Загрузка сборки модов ({dl_mb:.1f} / {tot_mb:.1f} МБ)... {pct}%"
                            else:
                                dl_mb = downloaded / 1048576
                                msg = f"Загрузка сборки модов ({dl_mb:.1f} МБ)..."
                            if progress_callback:
                                progress_callback(downloaded, total_bytes or downloaded, msg)

            if tmp_zip.is_file() and tmp_zip.stat().st_size > 1000000:
                download_success = True
                break
        except Exception as dle:
            logger.warning(f"Modpack URL {target_url} failed: {dle}")
            last_dl_error = dle
            tmp_zip.unlink(missing_ok=True)

    if not download_success:
        raise RuntimeError(f"Не удалось загрузить моды ни с одного зеркала ({last_dl_error}). Проверьте подключение к интернету.")

    try:
        # Transactional extraction of downloaded zip via staging
        staging_dir = game_dir / ".modpack_staging"
        shutil.rmtree(staging_dir, ignore_errors=True)
        staging_dir.mkdir(parents=True, exist_ok=True)
        try:
            with zipfile.ZipFile(tmp_zip, "r") as zf:
                safe_extract_zip(zf, staging_dir, progress_callback, "Распаковка сборки модов")

            for staged_f in staging_dir.rglob("*"):
                if staged_f.is_file():
                    rel_p = staged_f.relative_to(staging_dir)
                    dest_f = game_dir / rel_p
                    dest_f.parent.mkdir(parents=True, exist_ok=True)
                    staged_f.replace(dest_f)

            purge_blacklisted_mods(game_dir)
            mod_count = len(list((game_dir / "mods").glob("*.jar")))
            marker_tmp = marker_file.with_suffix(".tmp")
            with open(marker_tmp, "w", encoding="utf-8") as f:
                json.dump({
                    "version": MODPACK_VERSION,
                    "installed_at": time.time(),
                    "mods_count": mod_count
                }, f, indent=2, ensure_ascii=False)
            marker_tmp.replace(marker_file)

            if progress_callback:
                progress_callback(100, 100, f"Сборка модов готова ({mod_count} модов)")
        finally:
            shutil.rmtree(staging_dir, ignore_errors=True)

    except Exception as e:
        logger.error(f"Failed to download modpack: {e}")
        raise RuntimeError(f"Не удалось загрузить моды ({e}). Проверьте подключение к интернету.") from e
    finally:
        if tmp_zip.is_file():
            try:
                tmp_zip.unlink()
            except Exception:
                pass


def handle_run(args):
    game_dir = Path(args.game_dir).resolve()
    game_dir.mkdir(parents=True, exist_ok=True)
    
    if args.server_port is not None and not (1 <= args.server_port <= 65535):
        raise ValueError(f"Порт сервера должен быть в диапазоне 1..65535 (получено: {args.server_port})")

    emit_event("progress", {"percent": 5, "status": "Инициализация параметров..."})
    
    # 1. Java Runtime Setup
    runtime_dir = game_dir.parent.parent / "runtime" if game_dir.parent.name == "instances" else game_dir.parent / "runtime"
    java_mgr = JavaManager(runtime_dir)
    emit_event("progress", {"percent": 15, "status": "Проверка Java 21..."})

    java_path = None
    if getattr(args, 'java_path', None) and Path(args.java_path).is_file():
        candidate_path = Path(args.java_path)
        if java_mgr.verify_java(candidate_path):
            java_path = candidate_path.resolve()

    if not java_path:
        def on_java_progress(curr, total, status):
            pct = 15 + int((curr / max(1, total)) * 20)
            emit_event("progress", {"percent": min(35, pct), "status": status})
            
        java_path = java_mgr.ensure_java_21(progress_callback=on_java_progress)

    if java_path is None:
        raise RuntimeError("Не удалось найти или установить Java 21. Проверьте интернет, свободное место и права записи.")
    if not java_mgr.verify_java(java_path):
        raise RuntimeError(f"Найденный исполняемый файл Java 21 не прошёл проверку: {java_path}")
    emit_event("progress", {"percent": 35, "status": f"Java 21 готова: {java_path.name}"})

    # 2. Minecraft & Loader Installation (Vanilla 1.21.1 + NeoForge 21.1.265)
    installer = Installer(game_dir)
    mc_version = str(getattr(args, "mc_version", "") or "1.21.1").strip()
    if not mc_version:
        mc_version = "1.21.1"
    loader_type = str(getattr(args, "loader", "neoforge") or "neoforge").strip().lower()
    loader_version = str(getattr(args, "loader_version", "") or "21.1.265").strip()
    
    def on_install_progress(curr, total, status):
        pct = 35 + int((curr / max(1, total)) * 25)
        emit_event("progress", {"percent": min(60, pct), "status": status})

    target_version_id = mc_version
    if loader_type == "neoforge":
        if not loader_version:
            raise ValueError("Для NeoForge требуется указать --loader-version (например 21.1.265)")
        emit_event("progress", {"percent": 35, "status": f"Проверка NeoForge {loader_version}..."})
        target_version_id = installer.install_neoforge(
            minecraft_version=mc_version,
            neoforge_version=loader_version,
            java_path=java_path,
            progress_callback=on_install_progress,
        )
    else:
        emit_event("progress", {"percent": 35, "status": f"Проверка Minecraft {mc_version}..."})
        installer.install_vanilla(mc_version, progress_callback=on_install_progress)

    emit_event("progress", {"percent": 60, "status": f"Версия Minecraft {mc_version} и {loader_type} готовы"})

    # 3. Modpack Synchronization via GitHub Repository Manifest
    emit_event("progress", {"percent": 62, "status": "Синхронизация файлов сборки..."})
    manifest_url = getattr(args, 'manifest_url', None) or DEFAULT_MANIFEST_URL
    sync_engine = SyncEngine(game_dir)
    def on_sync_progress(curr, total, status):
        pct = 62 + int((curr / max(1, total)) * 28)
        emit_event("progress", {"percent": min(90, pct), "status": status})

    try:
        sync_engine.sync(manifest_url, progress_callback=on_sync_progress)
        emit_event("progress", {"percent": 90, "status": "Файлы сборки синхронизированы"})
    except (SyncNetworkError, TimeoutError, OSError, urllib.error.URLError) as e:
        logger.warning(f"Modpack sync encountered network issue: {e}. Checking local verified files...")
        manifest_cache = game_dir / ".last_manifest.json"
        is_locally_verified = False
        if manifest_cache.is_file():
            try:
                with open(manifest_cache, "r", encoding="utf-8") as f:
                    cached_manifest = json.load(f)
                invalid_files = sync_engine.verify_local_installation(cached_manifest)
                if not invalid_files:
                    is_locally_verified = True
                    mod_count = len(list((game_dir / "mods").glob("*.jar"))) if (game_dir / "mods").is_dir() else 0
                    logger.info(f"Local installation verified intact ({mod_count} mods) despite network error.")
                    emit_event("progress", {"percent": 90, "status": f"Проверенная локальная сборка готова ({mod_count} модов)"})
                else:
                    logger.warning(f"Local installation has invalid/missing files: {invalid_files[:5]}")
            except Exception as ve:
                logger.warning(f"Failed to verify local cache: {ve}")

        if not is_locally_verified:
            modpack_zip_url = getattr(args, 'modpack_url', None)
            if modpack_zip_url:
                logger.info("Attempting fallback ensure_modpack from provided modpack zip URL...")
                try:
                    ensure_modpack(
                        game_dir=game_dir,
                        modpack_url=modpack_zip_url,
                        progress_callback=on_sync_progress,
                    )
                    emit_event("progress", {"percent": 90, "status": "Файлы сборки готовы (резерв)"})
                    is_locally_verified = True
                except Exception as fallback_err:
                    logger.warning(f"Fallback ensure_modpack failed: {fallback_err}")

            if not is_locally_verified:
                suggestion = "Проверьте интернет-соединение или повторите запуск позже."
                if isinstance(e, SyncNetworkError) and not e.retryable:
                    suggestion = "Ссылка на файл сборки недоступна (код ошибки HTTP); требуется обновить manifest."
                msg = f"Не удалось синхронизировать файлы сборки: {e}. {suggestion}"
                emit_event("error", {"message": msg})
                raise RuntimeError(msg) from e

    # Always clean blacklisted mods before launching
    purge_blacklisted_mods(game_dir)

    emit_event("progress", {"percent": 92, "status": "Формирование аргументов запуска..."})

    # 4. Auth & Launch Options
    auth_token = os.environ.get("MINECRAFT_AUTH_TOKEN") or args.token or "0"
    auth_options = AuthManager.format_minecraft_options(
        session={
            "username": args.username,
            "uuid": args.uuid,
            "token": auth_token,
        },
        allocated_ram_mb=args.ram,
        server_host=args.server_host,
        server_port=args.server_port,
    )

    # Write anti-bypass launcher session marker
    try:
        session_file = game_dir / ".lowuncher_session.json"
        session_payload = {
            "launcher": "Lowuncher",
            "version": "1.0.7",
            "username": args.username,
            "uuid": args.uuid,
            "token": hashlib.sha256(f"Lowuncher-Verified:{args.username}:{args.uuid}:6233".encode("utf-8")).hexdigest(),
        }
        with open(session_file, "w", encoding="utf-8") as sf:
            json.dump(session_payload, sf, indent=2)
    except Exception as e:
        logger.warning(f"Could not write lowuncher session marker: {e}")

    # Ensure official multiplayer servers are pre-loaded in game
    ensure_servers_dat(game_dir)

    # 5. Launch
    emit_event("progress", {"percent": 98, "status": "Запуск процесса Minecraft..."})
    launcher = GameLauncher(game_dir)
    
    def on_game_log(line: str):
        emit_event("log", {"line": line})

    def on_game_exit(code: int, history: list):
        emit_event("exit", {"code": code, "lines_recorded": len(history)})

    proc = launcher.launch(
        version_id=target_version_id,
        java_path=java_path,
        options=auth_options,
        on_log=on_game_log,
        on_exit=on_game_exit,
    )

    def handle_shutdown(signum, frame):
        logger.info(f"Signal {signum} received in bridge. Terminating Minecraft process {proc.process.pid}...")
        proc.kill()
        sys.exit(0)

    try:
        signal.signal(signal.SIGTERM, handle_shutdown)
        signal.signal(signal.SIGINT, handle_shutdown)
    except Exception:
        pass

    emit_event("started", {"pid": proc.process.pid, "version": target_version_id})
    # Wait for game process to complete
    proc.process.wait()
    if proc.process.returncode != 0:
        logger.error(f"Minecraft process exited with code {proc.process.returncode}")
        last_logs = "\n".join(list(proc.log_history)[-8:]) if proc.log_history else ""
        if last_logs:
            emit_event("error", {"message": f"Minecraft аварийно завершился (код {proc.process.returncode}):\n{last_logs}"})
        sys.exit(proc.process.returncode)


def main():
    parser = argparse.ArgumentParser(description="Lowuncher Python Launch Bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run", help="Install and launch Minecraft")
    run_parser.add_argument("--game-dir", required=True, help="Path to instance game directory")
    run_parser.add_argument("--mc-version", default="1.21.1", help="Minecraft version")
    run_parser.add_argument("--loader", default="neoforge", choices=["vanilla", "neoforge"])
    run_parser.add_argument("--loader-version", default="21.1.72", help="Loader version (e.g. 21.1.72)")
    run_parser.add_argument("--username", default="Player", help="Player in-game username")
    run_parser.add_argument("--uuid", default="", help="Player UUID")
    run_parser.add_argument("--token", default="0", help="Session token")
    run_parser.add_argument("--ram", type=int, default=4096, help="RAM in MB")
    run_parser.add_argument("--manifest-url", default=None, help="Remote manifest URL for mods sync")
    run_parser.add_argument("--modpack-url", default=None, help="Direct URL to modpack zip archive")
    run_parser.add_argument("--server-host", default=None, help="Server IP to auto-connect")
    run_parser.add_argument("--server-port", type=int, default=None, help="Server port")
    run_parser.add_argument("--java-path", default=None, help="Explicit path to Java 21 executable")

    args = parser.parse_args()

    if args.command == "run":
        try:
            handle_run(args)
        except Exception as e:
            logger.exception("Bridge execution failed")
            emit_event("error", {"message": str(e)})
            sys.exit(1)


if __name__ == "__main__":
    main()
