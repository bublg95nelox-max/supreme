"""
Configuration and directory management for Lowuncher.
"""
import os
import sys
import json
import logging
from pathlib import Path
from typing import Any, Dict

logger = logging.getLogger("lowuncher.config")


def get_default_data_dir() -> Path:
    """
    Returns the root directory for Lowuncher:
    - Windows: %APPDATA%/.lowuncher
    - Linux / macOS: ~/.lowuncher
    Can be overridden via LOWUNCHER_DIR environment variable.
    """
    custom_dir = os.environ.get("LOWUNCHER_DIR")
    if custom_dir:
        path = Path(custom_dir).expanduser().resolve()
    elif sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        if appdata:
            path = Path(appdata) / ".lowuncher"
        else:
            path = Path.home() / ".lowuncher"
    else:
        path = Path.home() / ".lowuncher"

    path.mkdir(parents=True, exist_ok=True)
    return path


def get_total_system_ram_mb() -> int:
    """
    Returns total system physical RAM in MB using pure Python built-in APIs
    (no external C-extensions like psutil required).
    """
    # 1. Windows: ctypes GlobalMemoryStatusEx
    if sys.platform == "win32":
        try:
            import ctypes

            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
                return int(stat.ullTotalPhys // (1024 * 1024))
        except Exception:
            pass

    # 2. Linux: /proc/meminfo
    meminfo = Path("/proc/meminfo")
    if meminfo.exists():
        try:
            with open(meminfo, "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        parts = line.split()
                        return int(parts[1]) // 1024
        except Exception:
            pass

    # 3. Unix POSIX fallback: os.sysconf
    try:
        page_size = os.sysconf("SC_PAGE_SIZE")
        phys_pages = os.sysconf("SC_PHYS_PAGES")
        return int((page_size * phys_pages) // (1024 * 1024))
    except Exception:
        pass

    # Safe fallback if everything fails
    return 16384


def calculate_recommended_ram_mb() -> int:
    """
    Calculates recommended RAM allocation:
    - Default is half of total system RAM.
    - If system has > 24 GB, cap recommended at 12 GB (12 * 1024 MB = 12288 MB).
    - Minimum sensible allocation is 3072 MB for NeoForge 1.21.1.
    """
    total_mb = get_total_system_ram_mb()

    half_mb = total_mb // 2
    # Snap to nearest 512 MB step
    half_mb = (half_mb // 512) * 512

    max_recommended = 12 * 1024  # 12288 MB (12 GB)
    recommended = min(half_mb, max_recommended)
    return max(3072, recommended)


class ConfigManager:
    """Manages reading and writing launcher_config.json."""

    DEFAULT_CONFIG: Dict[str, Any] = {
        "username": "Player",
        "auth_type": "offline",  # 'offline' or 'microsoft'
        "microsoft_account": {},
        "allocated_ram_mb": 0,  # 0 signals to compute dynamically from system RAM
        "custom_jvm_args": "-XX:+UseG1GC -XX:+ParallelRefProcEnabled",
        "custom_java_path": "",
        "server_host": "89.248.236.145",
        "server_port": 27160,
        "backup_server_host": "ozdangalla.netmc.ru",
        "backup_server_port": 25565,
        "manifest_url": "",
        "auto_update": True,
        "close_launcher_on_launch": False,
        "show_console_on_crash": True,
    }

    def __init__(self, data_dir: Path | None = None):
        self.data_dir = data_dir or get_default_data_dir()
        self.config_path = self.data_dir / "launcher_config.json"
        self.runtime_dir = self.data_dir / "runtime"
        self.game_dir = self.data_dir / "game"
        
        # Ensure standard folders exist
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self.game_dir.mkdir(parents=True, exist_ok=True)

        self._data: Dict[str, Any] = self._load()

    def _load(self) -> Dict[str, Any]:
        data = self.DEFAULT_CONFIG.copy()
        if self.config_path.exists():
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                    if isinstance(loaded, dict):
                        data.update(loaded)
            except Exception as e:
                logger.warning(f"Failed to load config from {self.config_path}: {e}. Using defaults.")
        return data

    def save(self) -> None:
        """Saves current configuration to launcher_config.json atomically."""
        temp_file = self.config_path.with_suffix(".tmp")
        try:
            with open(temp_file, "w", encoding="utf-8") as f:
                json.dump(self._data, f, indent=4, ensure_ascii=False)
            temp_file.replace(self.config_path)
        except Exception as e:
            logger.error(f"Failed to save config to {self.config_path}: {e}")
            if temp_file.exists():
                temp_file.unlink(missing_ok=True)

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def set(self, key: str, value: Any, auto_save: bool = True) -> None:
        self._data[key] = value
        if auto_save:
            self.save()

    @property
    def username(self) -> str:
        value = self._data.get("username")
        if not isinstance(value, str) or not value.strip():
            return "Player"
        return value.strip()

    @username.setter
    def username(self, val: str) -> None:
        self.set("username", val)

    @property
    def allocated_ram_mb(self) -> int:
        value = self._data.get("allocated_ram_mb")
        try:
            val = int(value)
        except (TypeError, ValueError):
            val = 0
        if val <= 0:
            val = calculate_recommended_ram_mb()
            self._data["allocated_ram_mb"] = val
        return val

    @allocated_ram_mb.setter
    def allocated_ram_mb(self, val: int) -> None:
        try:
            int_val = int(val)
        except (TypeError, ValueError):
            int_val = 0
        self.set("allocated_ram_mb", int_val)

    @property
    def manifest_url(self) -> str:
        value = self._data.get("manifest_url")
        return value.strip() if isinstance(value, str) else ""

    @manifest_url.setter
    def manifest_url(self, val: str) -> None:
        self.set("manifest_url", val)
