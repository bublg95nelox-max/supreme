"""
Minecraft process launch and monitoring module.
"""
import os
import sys
import logging
import subprocess
import threading
from collections import deque
from pathlib import Path
from typing import Callable, Optional, List, Dict, Any, Deque

# Ensure bundled libraries in libraries/python are available
_root_dir = Path(__file__).resolve().parent.parent
_bundled_libs = _root_dir / "libraries" / "python"
if _bundled_libs.is_dir() and str(_bundled_libs) not in sys.path:
    sys.path.insert(0, str(_bundled_libs))

import minecraft_launcher_lib as mll

logger = logging.getLogger("lowuncher.launcher")


class MinecraftProcess:
    """Represents a running Minecraft instance."""

    def __init__(
        self,
        process: subprocess.Popen,
        on_log: Optional[Callable[[str], None]] = None,
        on_exit: Optional[Callable[[int, List[str]], None]] = None,
    ):
        self.process = process
        self.on_log = on_log
        self.on_exit = on_exit
        self.log_history: Deque[str] = deque(maxlen=2000)
        self._thread = threading.Thread(target=self._monitor_output, daemon=True)
        self._thread.start()

    def _monitor_output(self) -> None:
        """Reads stdout/stderr asynchronously and records output."""
        try:
            if self.process.stdout:
                for line in iter(self.process.stdout.readline, ""):
                    cleaned_line = line.rstrip()
                    if cleaned_line:
                        self.log_history.append(cleaned_line)
                        if self.on_log:
                            self.on_log(cleaned_line)
        except Exception as e:
            logger.warning(f"Error reading game output: {e}")
        finally:
            self.process.wait()
            exit_code = self.process.returncode
            logger.info(f"Minecraft process exited with code {exit_code}")
            if self.on_exit:
                self.on_exit(exit_code, list(self.log_history))

    def is_running(self) -> bool:
        return self.process.poll() is None

    def terminate(self) -> None:
        if self.is_running():
            try:
                self.process.terminate()
            except Exception:
                pass

    def kill(self) -> None:
        if self.is_running():
            try:
                self.process.kill()
            except Exception:
                pass


class GameLauncher:
    """Constructs command and launches Minecraft."""

    def __init__(self, game_dir: Path):
        self.game_dir = game_dir

    def build_command(
        self,
        version_id: str,
        java_path: Path,
        options: Dict[str, Any],
    ) -> List[str]:
        """
        Builds the complete command list to launch Minecraft.
        """
        launch_options = options.copy()
        launch_options["executablePath"] = str(java_path)
        launch_options["gameDirectory"] = str(self.game_dir)

        # Build command using minecraft-launcher-lib
        command = mll.command.get_minecraft_command(
            version=version_id,
            minecraft_directory=str(self.game_dir),
            options=launch_options,
        )

        # minecraft-launcher-lib appends -Dminecraft.launcher.brand=minecraft-launcher-lib
        # at the end of the argument list, which overrides any custom brand in jvmArguments.
        # Replace or sanitize all brand arguments to ensure Lowuncher is the active launcher.
        sanitized_command = []
        for arg in command:
            if arg.startswith("-Dminecraft.launcher.brand="):
                sanitized_command.append("-Dminecraft.launcher.brand=Lowuncher")
            elif arg.startswith("-Dminecraft.launcher.version="):
                sanitized_command.append("-Dminecraft.launcher.version=1.0.5")
            else:
                sanitized_command.append(arg)

        # Ensure security properties are present before main class
        has_active = any(a.startswith("-Dlowuncher.active=") for a in sanitized_command)
        if not has_active:
            # Insert right before the main class
            sanitized_command.insert(-1, "-Dlowuncher.active=true")

        return sanitized_command

    def launch(
        self,
        version_id: str,
        java_path: Path,
        options: Dict[str, Any],
        on_log: Optional[Callable[[str], None]] = None,
        on_exit: Optional[Callable[[int, List[str]], None]] = None,
    ) -> MinecraftProcess:
        """
        Launches the Minecraft process.
        """
        command = self.build_command(version_id, java_path, options)
        logger.info(f"Launching Minecraft with command: {' '.join(command[:5])} ...")

        # Startupinfo to hide console window on Windows if desired
        startupinfo = None
        creationflags = 0
        if sys.platform == "win32":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW

        proc = subprocess.Popen(
            command,
            cwd=str(self.game_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            startupinfo=startupinfo,
            creationflags=creationflags,
        )

        return MinecraftProcess(proc, on_log=on_log, on_exit=on_exit)
