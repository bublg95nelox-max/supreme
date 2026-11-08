#!/usr/bin/env python3
"""
Generates multi-resolution icons (.ico, .png, .icns) from the downloaded
glossy blue 'L' logo, and sets up folder and panel/taskbar icons.
"""
import io
import os
import sys
import struct
import subprocess
from pathlib import Path
from PIL import Image, ImageFilter
import numpy as np

WORKSPACE_ROOT = Path("/mnt/games/lowuncher").resolve()
SOURCE_IMG = Path("/home/saidbl/Загрузки/Глянцевая синяя буква L на тёмном фоне.png")


def create_smooth_squircle(source_path: Path) -> Image.Image:
    """Removes the black border outside the squircle and adds smooth anti-aliased transparency."""
    im = Image.open(source_path).convert("RGB")
    arr = np.array(im)
    h, w = arr.shape[:2]

    # BFS from 4 corners to find dark background
    gray = np.max(arr, axis=2)
    visited = np.zeros((h, w), dtype=bool)
    queue = [(0, 0), (0, w - 1), (h - 1, 0), (h - 1, w - 1)]
    for y, x in queue:
        visited[y, x] = True

    idx = 0
    threshold = 4
    while idx < len(queue):
        cy, cx = queue[idx]
        idx += 1
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = cy + dy, cx + dx
            if 0 <= ny < h and 0 <= nx < w and not visited[ny, nx]:
                if gray[ny, nx] <= threshold:
                    visited[ny, nx] = True
                    queue.append((ny, nx))

    # Mask: 255 where inside, 0 where outside
    mask_arr = np.where(visited, 0, 255).astype(np.uint8)
    mask_img = Image.fromarray(mask_arr, mode="L")
    smooth_mask = mask_img.filter(ImageFilter.GaussianBlur(radius=1.2))

    out = im.convert("RGBA")
    out.putalpha(smooth_mask)

    # Crop to squircle with symmetric margin
    alpha_arr = np.array(smooth_mask)
    coords = np.argwhere(alpha_arr > 10)
    y0, x0 = coords.min(axis=0)
    y1, x1 = coords.max(axis=0)
    size = max(x1 - x0, y1 - y0)
    pad = int(size * 0.04)

    crop_box = (x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    cropped = out.crop(crop_box)
    return cropped.resize((1024, 1024), Image.Resampling.LANCZOS)


def generate_icns(icon_1024: Image.Image, output_path: Path):
    """Generates an Apple ICNS file containing 128, 256, 512, and 1024 PNG streams."""
    chunks = []
    types = [
        (b"ic07", 128),
        (b"ic08", 256),
        (b"ic09", 512),
        (b"ic10", 1024),
    ]
    for tag, sz in types:
        resized = icon_1024.resize((sz, sz), Image.Resampling.LANCZOS)
        buf = io.BytesIO()
        resized.save(buf, format="PNG")
        data = buf.getvalue()
        chunks.append(tag + struct.pack(">I", 8 + len(data)) + data)

    total_len = 8 + sum(len(c) for c in chunks)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", total_len) + b"".join(chunks))


def main():
    if not SOURCE_IMG.exists():
        print(f"Error: source image not found at {SOURCE_IMG}", file=sys.stderr)
        sys.exit(1)

    print(f"Processing source image: {SOURCE_IMG}...")
    icon_1024 = create_smooth_squircle(SOURCE_IMG)

    # 1. Save standard PNGs
    temp_1024_path = WORKSPACE_ROOT / "build" / "icon_1024.png"
    temp_1024_path.parent.mkdir(parents=True, exist_ok=True)
    icon_1024.save(temp_1024_path, format="PNG")

    icon_512 = icon_1024.resize((512, 512), Image.Resampling.LANCZOS)
    icon_256 = icon_1024.resize((256, 256), Image.Resampling.LANCZOS)

    # App assets
    app_img_dir = WORKSPACE_ROOT / "app" / "assets" / "images"
    app_img_dir.mkdir(parents=True, exist_ok=True)
    icon_512.save(app_img_dir / "SealCircle.png", format="PNG")
    icon_512.save(app_img_dir / "LoadingSeal.png", format="PNG")
    icon_512.save(WORKSPACE_ROOT / "build" / "icon.png", format="PNG")
    icon_512.save(WORKSPACE_ROOT / "icon.png", format="PNG")

    # Multi-resolution PNGs for Linux icons
    sizes = [16, 24, 32, 48, 64, 128, 256, 512, 1024]
    for sz in sizes:
        sz_dir = WORKSPACE_ROOT / "build" / "icons" / f"{sz}x{sz}"
        sz_dir.mkdir(parents=True, exist_ok=True)
        resized = icon_1024.resize((sz, sz), Image.Resampling.LANCZOS)
        resized.save(sz_dir / "icon.png", format="PNG")
        resized.save(WORKSPACE_ROOT / "build" / "icons" / f"{sz}x{sz}.png", format="PNG")

        # Also install into ~/.local/share/icons/hicolor/{sz}x{sz}/apps/
        hicolor_dir = Path.home() / ".local" / "share" / "icons" / "hicolor" / f"{sz}x{sz}" / "apps"
        try:
            hicolor_dir.mkdir(parents=True, exist_ok=True)
            resized.save(hicolor_dir / "lowuncher.png", format="PNG")
        except Exception as e:
            print(f"Notice: Could not write hicolor icon for {sz}: {e}")

    # 2. Windows Multi-Resolution ICO
    print("Generating Windows multi-resolution .ico...")
    ico_targets = [
        WORKSPACE_ROOT / "build" / "icon.ico",
        app_img_dir / "SealCircle.ico",
        WORKSPACE_ROOT / "icon.ico",
    ]
    # Use magick to create full 7-resolution 32-bit ICO
    subprocess.run([
        "magick",
        str(temp_1024_path),
        "-define",
        "icon:auto-resize=256,128,64,48,32,24,16",
        str(ico_targets[0])
    ], check=True)

    # Copy to other targets
    import shutil
    for target in ico_targets[1:]:
        shutil.copy2(ico_targets[0], target)

    # 3. macOS ICNS
    print("Generating macOS .icns...")
    generate_icns(icon_1024, WORKSPACE_ROOT / "build" / "icon.icns")
    generate_icns(icon_1024, app_img_dir / "minecraft.icns")

    # 4. Folder icons & desktop integration
    # Linux folder custom icon (.directory)
    directory_file = WORKSPACE_ROOT / ".directory"
    directory_content = f"""[Desktop Entry]
Icon={WORKSPACE_ROOT / 'icon.png'}
"""
    with open(directory_file, "w", encoding="utf-8") as f:
        f.write(directory_content)

    # Windows folder custom icon (desktop.ini)
    desktop_ini = WORKSPACE_ROOT / "desktop.ini"
    ini_content = """[.ShellClassInfo]
IconResource=icon.ico,0
[ViewState]
Mode=
Vid=
FolderType=Generic
"""
    with open(desktop_ini, "w", encoding="utf-8") as f:
        f.write(ini_content)

    # Linux .desktop launcher file
    desktop_entry_path = Path.home() / ".local" / "share" / "applications" / "lowuncher.desktop"
    desktop_entry_path.parent.mkdir(parents=True, exist_ok=True)
    desktop_content = f"""[Desktop Entry]
Version=1.0
Type=Application
Name=Lowuncher
GenericName=Minecraft Launcher
Comment=School Minecraft Community Launcher
Exec=npm start --prefix {WORKSPACE_ROOT}
Icon={WORKSPACE_ROOT / 'icon.png'}
Terminal=false
Categories=Game;ActionGame;
StartupWMClass=lowuncher
"""
    with open(desktop_entry_path, "w", encoding="utf-8") as f:
        f.write(desktop_content)

    print("\n✅ All icons successfully created and configured:")
    print(f"  • Windows ICO: {WORKSPACE_ROOT / 'build' / 'icon.ico'}")
    print(f"  • App Window ICO: {app_img_dir / 'SealCircle.ico'}")
    print(f"  • App Window PNG: {app_img_dir / 'SealCircle.png'}")
    print(f"  • Linux AppImage / Build PNG: {WORKSPACE_ROOT / 'build' / 'icon.png'}")
    print(f"  • macOS ICNS: {WORKSPACE_ROOT / 'build' / 'icon.icns'}")
    print(f"  • Windows Folder ini: {desktop_ini}")
    print(f"  • Linux Folder .directory: {directory_file}")
    print(f"  • Desktop Entry: {desktop_entry_path}")


if __name__ == "__main__":
    main()
