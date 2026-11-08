#!/usr/bin/env python3
"""
Manifest Generator for Lowuncher administrators.
Scans modpack directory (mods, configs, etc.) and generates a manifest.json.
"""
import os
import sys
import json
import hashlib
import argparse
from pathlib import Path
from typing import List, Dict, Any


def compute_sha256(file_path: Path) -> str:
    """Computes SHA-256 checksum of a file."""
    sha256 = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest()


def generate_manifest(
    source_dir: Path,
    base_download_url: str,
    minecraft_version: str = "1.21.1",
    neoforge_version: str = "21.1.72",
    neoforge_installer_url: str = "",
    preserve_paths: bool = False,
) -> Dict[str, Any]:
    """
    Scans source_dir for mods, config, shaderpacks, etc. and builds the manifest dict.
    """
    tracked_dirs = ["mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks"]
    files: List[Dict[str, Any]] = []

    # Clean base url trailing slash
    base_url = base_download_url.rstrip("/") if base_download_url else ""

    for tracked in tracked_dirs:
        folder = source_dir / tracked
        if not folder.exists():
            continue

        for file_path in folder.rglob("*"):
            if not file_path.is_file():
                continue

            rel_path = file_path.relative_to(source_dir).as_posix()
            file_size = file_path.stat().st_size
            file_hash = compute_sha256(file_path)

            # Files in config/ or defaultoptions shouldn't be overwritten once created
            overwrite = not (rel_path.startswith("config/") or "options.txt" in rel_path)

            if base_url:
                url_suffix = rel_path if preserve_paths else file_path.name
                file_url = f"{base_url}/{url_suffix}"
            else:
                file_url = ""

            entry = {
                "path": rel_path,
                "sha256": file_hash,
                "size": file_size,
                "url": file_url,
                "required": True,
                "overwrite": overwrite,
            }
            files.append(entry)

    manifest = {
        "launcher_version": "1.0.0",
        "minecraft_version": minecraft_version,
        "loader": {
            "type": "neoforge",
            "version": neoforge_version,
            "installer_url": neoforge_installer_url,
        },
        "java_version": 21,
        "total_files": len(files),
        "files": files,
    }
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Generate manifest.json for Lowuncher")
    parser.add_argument("dir", help="Path to modpack folder (containing mods/, config/, etc.)")
    parser.add_argument(
        "--output",
        "-o",
        default="manifest.json",
        help="Output manifest JSON path (default: manifest.json)",
    )
    parser.add_argument(
        "--base-url",
        "-u",
        default="",
        help="Base download URL for GitHub Release assets (e.g. https://github.com/org/repo/releases/latest/download)",
    )
    parser.add_argument(
        "--preserve-paths",
        action="store_true",
        help="Preserve folder paths in download URLs (for standard HTTP/Nginx servers instead of flat GitHub Releases)",
    )
    parser.add_argument(
        "--mc-version",
        default="1.21.1",
        help="Minecraft version (default: 1.21.1)",
    )
    parser.add_argument(
        "--neoforge-version",
        default="21.1.72",
        help="NeoForge version (default: 21.1.72)",
    )
    parser.add_argument(
        "--neoforge-installer-url",
        default="",
        help="Custom URL for NeoForge installer jar (e.g. GitHub Release asset)",
    )

    args = parser.parse_args()
    source_dir = Path(args.dir).resolve()
    if not source_dir.exists():
        print(f"Error: directory {source_dir} does not exist!", file=sys.stderr)
        sys.exit(1)

    print(f"Scanning {source_dir}...")
    manifest = generate_manifest(
        source_dir=source_dir,
        base_download_url=args.base_url,
        minecraft_version=args.mc_version,
        neoforge_version=args.neoforge_version,
        neoforge_installer_url=args.neoforge_installer_url,
        preserve_paths=args.preserve_paths,
    )

    output_path = Path(args.output).resolve()
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    print(f"Successfully generated {output_path} with {manifest['total_files']} files.")


if __name__ == "__main__":
    main()
