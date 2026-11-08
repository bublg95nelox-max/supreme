"""
Bridge CLI for Lowuncher.
Acts as the communication bridge between HeliosLauncher (Node.js/Electron)
and the Python Minecraft launch engine (minecraft-launcher-lib, Java 21, NeoForge, SyncEngine).
"""
import sys
import os
import json
import hashlib
import argparse
import logging
import signal
from pathlib import Path

# Ensure lowuncher root and bundled libraries are on sys.path FIRST
_root_dir = Path(__file__).resolve().parent.parent
if str(_root_dir) not in sys.path:
    sys.path.insert(0, str(_root_dir))
_bundled_libs = _root_dir / "libraries" / "python"
if _bundled_libs.is_dir() and str(_bundled_libs) not in sys.path:
    sys.path.insert(0, str(_bundled_libs))

import requests

from core.java_manager import JavaManager
from core.installer import Installer
from core.sync_engine import SyncEngine
from core.launcher import GameLauncher
from core.auth import AuthManager

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("lowuncher.bridge")


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


def handle_run(args):
    game_dir = Path(args.game_dir).resolve()
    game_dir.mkdir(parents=True, exist_ok=True)
    
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
    emit_event("progress", {"percent": 35, "status": f"Java 21 готова: {java_path.name}"})

    # 2. Modpack Synchronization (if manifest provided)
    if args.manifest_url:
        emit_event("progress", {"percent": 40, "status": "Синхронизация файлов сборки..."})
        sync_engine = SyncEngine(game_dir)
        def on_sync_progress(curr, total, status):
            pct = 40 + int((curr / max(1, total)) * 25)
            emit_event("progress", {"percent": min(65, pct), "status": status})
        try:
            sync_engine.sync(args.manifest_url, progress_callback=on_sync_progress)
            emit_event("progress", {"percent": 65, "status": "Файлы сборки синхронизированы"})
        except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
            logger.warning(f"Modpack sync skipped because network is unavailable: {e}")
            emit_event("progress", {"percent": 65, "status": "Синхронизация пропущена: сеть недоступна"})
        except Exception:
            logger.exception("Modpack synchronization failed")
            emit_event("error", {"message": "Синхронизация сборки не прошла проверку целостности"})
            raise

    # 3. Minecraft & Loader Installation
    installer = Installer(game_dir)
    mc_version = args.mc_version
    loader_type = (args.loader or "vanilla").lower()
    loader_version = args.loader_version
    
    def on_install_progress(curr, total, status):
        pct = 65 + int((curr / max(1, total)) * 25)
        emit_event("progress", {"percent": min(90, pct), "status": status})

    target_version_id = mc_version
    if loader_type == "neoforge":
        if not loader_version:
            raise ValueError("Для NeoForge требуется указать --loader-version (например 21.1.72)")
        emit_event("progress", {"percent": 70, "status": f"Проверка NeoForge {loader_version}..."})
        target_version_id = installer.install_neoforge(
            minecraft_version=mc_version,
            neoforge_version=loader_version,
            java_path=java_path,
            progress_callback=on_install_progress,
        )
    else:
        emit_event("progress", {"percent": 70, "status": f"Проверка Minecraft {mc_version}..."})
        installer.install_vanilla(mc_version, progress_callback=on_install_progress)

    emit_event("progress", {"percent": 90, "status": "Формирование аргументов запуска..."})

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
            "version": "1.0.2",
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
