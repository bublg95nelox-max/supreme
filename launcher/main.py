#!/usr/bin/env python3
"""
Lowuncher - Python Entry Point and Launch Bridge.
Coordinates the Python Minecraft engine and provides quick start.
"""
import sys
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent
    if len(sys.argv) > 1:
        from core.bridge_cli import main as bridge_main
        bridge_main()
    else:
        print("Starting Lowuncher GUI (Electron)...")
        try:
            subprocess.run(["npm", "start"], cwd=str(root))
        except FileNotFoundError:
            print("npm not found. Please install Node.js or run: python main.py run --help")


if __name__ == "__main__":
    main()
