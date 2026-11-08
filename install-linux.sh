#!/usr/bin/env bash
# ==============================================================================
# Lowuncher Linux Installer
# Automated installer for Lowuncher Minecraft Launcher on Linux
# ==============================================================================

set -e

BOLD="\033[1m"
GREEN="\033[32m"
BLUE="\033[34m"
YELLOW="\033[33m"
CYAN="\033[36m"
RESET="\033[0m"

echo -e "${BLUE}${BOLD}"
echo "  ╔══════════════════════════════════════════════════════════╗"
echo "  ║                  LOWUNCHER LINUX INSTALLER              ║"
echo "  ║          Официальный лаунчер Minecraft 1.21.1            ║"
echo "  ╚══════════════════════════════════════════════════════════╝"
echo -e "${RESET}"

INSTALL_DIR="$HOME/.local/share/lowuncher"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor"

mkdir -p "$INSTALL_DIR"
mkdir -p "$BIN_DIR"
mkdir -p "$DESKTOP_DIR"

SCRIPT_DIR=""
if [ -n "${BASH_SOURCE[0]}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

APPIMAGE_SOURCE=""

# 1. Locate local AppImage if available
if [ -n "$SCRIPT_DIR" ]; then
    for candidate in \
        "$SCRIPT_DIR/dist/Lowuncher-1.0.4-linux-x86_64.AppImage" \
        "$SCRIPT_DIR/dist/Lowuncher-1.0.3-linux-x86_64.AppImage" \
        "$SCRIPT_DIR/Lowuncher-1.0.4-linux-x86_64.AppImage" \
        "$SCRIPT_DIR/Lowuncher-1.0.3-linux-x86_64.AppImage" \
        "$SCRIPT_DIR"/Lowuncher-*-linux-x86_64.AppImage \
        "$SCRIPT_DIR/Lowuncher.AppImage"; do
        if [ -f "$candidate" ]; then
            APPIMAGE_SOURCE="$candidate"
            break
        fi
    done
fi

if [ -z "$APPIMAGE_SOURCE" ]; then
    for candidate in \
        "/mnt/games/lowuncher/dist/Lowuncher-1.0.4-linux-x86_64.AppImage" \
        "/mnt/games/lowuncher/dist/Lowuncher-1.0.3-linux-x86_64.AppImage" \
        "/mnt/games/lowuncher/dist"/Lowuncher-*-linux-x86_64.AppImage; do
        if [ -f "$candidate" ]; then
            APPIMAGE_SOURCE="$candidate"
            break
        fi
    done
fi

# 2. Download from GitHub Releases if not found locally
if [ -z "$APPIMAGE_SOURCE" ] || [ ! -f "$APPIMAGE_SOURCE" ]; then
    echo -e "${CYAN}▶ Загрузка актуального релиза Lowuncher с GitHub...${RESET}"
    if command -v curl &>/dev/null; then
        curl -fSL "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.4/Lowuncher-1.0.4-linux-x86_64.AppImage" -o "$INSTALL_DIR/Lowuncher.AppImage" || \
        curl -fSL "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.3/Lowuncher-1.0.3-linux-x86_64.AppImage" -o "$INSTALL_DIR/Lowuncher.AppImage" || \
        curl -fSL "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.2/Lowuncher-1.0.2-linux-x86_64.AppImage" -o "$INSTALL_DIR/Lowuncher.AppImage"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.4/Lowuncher-1.0.4-linux-x86_64.AppImage" -O "$INSTALL_DIR/Lowuncher.AppImage" || \
        wget -q --show-progress "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.3/Lowuncher-1.0.3-linux-x86_64.AppImage" -O "$INSTALL_DIR/Lowuncher.AppImage" || \
        wget -q --show-progress "https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.2/Lowuncher-1.0.2-linux-x86_64.AppImage" -O "$INSTALL_DIR/Lowuncher.AppImage"
    fi
    if [ -s "$INSTALL_DIR/Lowuncher.AppImage" ]; then
        chmod +x "$INSTALL_DIR/Lowuncher.AppImage"
        APPIMAGE_SOURCE="$INSTALL_DIR/Lowuncher.AppImage"
    fi
else
    echo -e "${CYAN}▶ Копирование файла лаунчера в $INSTALL_DIR...${RESET}"
    if [ "$APPIMAGE_SOURCE" != "$INSTALL_DIR/Lowuncher.AppImage" ]; then
        cp "$APPIMAGE_SOURCE" "$INSTALL_DIR/Lowuncher.AppImage"
    fi
    chmod +x "$INSTALL_DIR/Lowuncher.AppImage"
fi

if [ ! -s "$INSTALL_DIR/Lowuncher.AppImage" ]; then
    echo -e "${YELLOW}Ошибка: Не удалось найти или скачать файл Lowuncher.AppImage.${RESET}"
    exit 1
fi

echo -e "${GREEN}✓ Исполняемый файл получен.${RESET}"

# 3. Extract AppImage to bypass FUSE requirements completely & enable instant startup
echo -e "${CYAN}▶ Распаковка файлов лаунчера (работает без FUSE/libfuse.so.2)...${RESET}"
cd "$INSTALL_DIR"
rm -rf squashfs-root app
"$INSTALL_DIR/Lowuncher.AppImage" --appimage-extract > /dev/null 2>&1 || true

if [ -d "$INSTALL_DIR/squashfs-root" ]; then
    mv "$INSTALL_DIR/squashfs-root" "$INSTALL_DIR/app"
    echo -e "${GREEN}✓ Лаунчер распакован в $INSTALL_DIR/app (мгновенный запуск без FUSE).${RESET}"
fi

# 4. Install Icons into system icon theme
echo -e "${CYAN}▶ Установка иконок приложения...${RESET}"
if [ -d "$INSTALL_DIR/app/usr/share/icons/hicolor" ]; then
    cp -r "$INSTALL_DIR/app/usr/share/icons/hicolor/"* "$ICON_DIR/" 2>/dev/null || true
fi
if [ -f "$INSTALL_DIR/app/lowuncher.png" ]; then
    cp "$INSTALL_DIR/app/lowuncher.png" "$INSTALL_DIR/icon.png"
fi

for sz in 16 24 32 48 64 128 256 512 1024; do
    SZ_DIR="$ICON_DIR/${sz}x${sz}/apps"
    mkdir -p "$SZ_DIR"
    if [ -f "$INSTALL_DIR/app/usr/share/icons/hicolor/${sz}x${sz}/apps/lowuncher.png" ]; then
        cp "$INSTALL_DIR/app/usr/share/icons/hicolor/${sz}x${sz}/apps/lowuncher.png" "$SZ_DIR/lowuncher.png"
    fi
done

if command -v gtk-update-icon-cache &>/dev/null; then
    gtk-update-icon-cache -f -t "$ICON_DIR" 2>/dev/null || true
fi
echo -e "${GREEN}✓ Системные иконки установлены.${RESET}"

# 5. Create lowuncher wrapper script in ~/.local/bin
cat << 'EOF' > "$BIN_DIR/lowuncher"
#!/usr/bin/env bash
export APPIMAGE_EXTRACT_AND_RUN=1

if [ -x "$HOME/.local/share/lowuncher/app/AppRun" ]; then
    exec "$HOME/.local/share/lowuncher/app/AppRun" "$@"
elif [ -x "$HOME/.local/share/lowuncher/app/lowuncher" ]; then
    exec "$HOME/.local/share/lowuncher/app/lowuncher" "$@"
elif [ -x "$HOME/.local/share/lowuncher/Lowuncher.AppImage" ]; then
    exec "$HOME/.local/share/lowuncher/Lowuncher.AppImage" --appimage-extract-and-run "$@"
else
    echo "Ошибка: Lowuncher не найден в $HOME/.local/share/lowuncher"
    exit 1
fi
EOF
chmod +x "$BIN_DIR/lowuncher"
echo -e "${GREEN}✓ Команда 'lowuncher' создана: $BIN_DIR/lowuncher${RESET}"

# 6. Create Desktop Entry (.desktop)
echo -e "${CYAN}▶ Создание ярлыка в меню приложений...${RESET}"
cat << EOF > "$DESKTOP_DIR/lowuncher.desktop"
[Desktop Entry]
Version=1.0
Type=Application
Name=Lowuncher
GenericName=Minecraft Launcher
Comment=Официальный лаунчер школьного сервера Minecraft NeoForge 1.21.1
Exec=$BIN_DIR/lowuncher %U
Icon=lowuncher
Terminal=false
Categories=Game;ActionGame;
StartupWMClass=lowuncher
Keywords=minecraft;game;launcher;lowuncher;
EOF
chmod +x "$DESKTOP_DIR/lowuncher.desktop"

# Also place on Desktop if ~/Рабочий стол or ~/Desktop exists
for desk in "$HOME/Desktop" "$HOME/Рабочий стол"; do
    if [ -d "$desk" ]; then
        cp "$DESKTOP_DIR/lowuncher.desktop" "$desk/lowuncher.desktop"
        chmod +x "$desk/lowuncher.desktop"
        echo -e "${GREEN}✓ Ярлык добавлен на рабочий стол: $desk${RESET}"
    fi
done

if command -v update-desktop-database &>/dev/null; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi

echo ""
echo -e "${GREEN}${BOLD}══════════════════════════════════════════════════════════════${RESET}"
echo -e "${GREEN}${BOLD}       LOWUNCHER УСПЕШНО УСТАНОВЛЕН НА ВАШ КОМПЬЮТЕР!         ${RESET}"
echo -e "${GREEN}${BOLD}══════════════════════════════════════════════════════════════${RESET}"
echo ""
echo -e "  • ${BOLD}Запуск из терминала:${RESET} lowuncher"
echo -e "  • ${BOLD}Меню приложений:${RESET} найдите 'Lowuncher' в меню вашей системы"
echo -e "  • ${BOLD}Папка установки:${RESET} $INSTALL_DIR"
echo ""

# Check if ~/.local/bin is in PATH
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo -e "${YELLOW}ℹ Внимание:${RESET} каталог $BIN_DIR не найден в переменной PATH вашей оболочки."
    echo -e "  Чтобы команда 'lowuncher' работала в терминале, добавьте в ваш ~/.bashrc / ~/.zshrc / ~/.config/fish/config.fish:"
    echo -e "  ${BOLD}export PATH=\"\$HOME/.local/bin:\$PATH\"${RESET}"
fi
echo ""
