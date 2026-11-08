#!/usr/bin/env python3
"""
Sets up portable Windows Embeddable Python in build/win-python.
Enables Lowuncher to be packaged for Windows with zero Python dependencies on the user's OS.
"""
import os
import sys
import shutil
import zipfile
import subprocess
from pathlib import Path
import requests

PYTHON_EMBED_URL = "https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip"


def setup_win_python():
    root = Path(__file__).resolve().parent.parent
    target_dir = root / "build" / "win-python"
    temp_zip = root / "build" / "python-embed.zip"
    target_dir.parent.mkdir(parents=True, exist_ok=True)

    if (target_dir / "python.exe").exists() and (target_dir / "Lib" / "site-packages" / "minecraft_launcher_lib").exists():
        print(f"Windows Embeddable Python is already set up in {target_dir}")
        return

    print(f"Downloading Windows Embeddable Python from {PYTHON_EMBED_URL}...")
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Lowuncher/1.0"}
    with requests.get(PYTHON_EMBED_URL, stream=True, headers=headers, timeout=30) as r:
        r.raise_for_status()
        with open(temp_zip, "wb") as f:
            for chunk in r.iter_content(chunk_size=65536):
                if chunk:
                    f.write(chunk)

    print(f"Extracting to {target_dir}...")
    if target_dir.exists():
        shutil.rmtree(target_dir, ignore_errors=True)
    target_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(temp_zip, "r") as zf:
        zf.extractall(target_dir)

    temp_zip.unlink(missing_ok=True)

    # Enable 'import site' in python311._pth (required for site-packages to work)
    pth_files = list(target_dir.glob("python*._pth"))
    if pth_files:
        pth_file = pth_files[0]
        content = pth_file.read_text(encoding="utf-8")
        # Uncomment 'import site' and ensure Lib/site-packages and . are present
        lines = content.splitlines()
        new_lines = []
        for line in lines:
            if line.strip() == "#import site":
                new_lines.append("import site")
            else:
                new_lines.append(line)
        if "Lib/site-packages" not in new_lines:
            new_lines.append("Lib/site-packages")
        if "." not in new_lines:
            new_lines.append(".")
        if ".." not in new_lines:
            new_lines.append("..")
        if "..\\.." not in new_lines:
            new_lines.append("..\\..")
        pth_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
        print(f"Patched {pth_file.name} to enable site-packages")

    # Install requirements for win_amd64 into Lib/site-packages
    site_packages = target_dir / "Lib" / "site-packages"
    site_packages.mkdir(parents=True, exist_ok=True)

    requirements_file = root / "requirements.txt"
    print(f"Installing {requirements_file} for Windows amd64 into {site_packages}...")
    cmd = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--target",
        str(site_packages),
        "--only-binary=:all:",
        "--platform",
        "win_amd64",
        "--python-version",
        "311",
        "-r",
        str(requirements_file),
    ]

    result = subprocess.run(cmd, check=True)
    if result.returncode == 0:
        print("Successfully prepared Windows Embeddable Python environment!")


if __name__ == "__main__":
    setup_win_python()
