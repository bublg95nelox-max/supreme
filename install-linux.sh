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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPIMAGE_SOURCE=""

# 1. Locate AppImage
if [ -f "$SCRIPT_DIR/dist/Lowuncher-1.0.0-linux-x86_64.AppImage" ]; then
    APPIMAGE_SOURCE="$SCRIPT_DIR/dist/Lowuncher-1.0.0-linux-x86_64.AppImage"
elif [ -f "$SCRIPT_DIR/Lowuncher-1.0.0-linux-x86_64.AppImage" ]; then
    APPIMAGE_SOURCE="$SCRIPT_DIR/Lowuncher-1.0.0-linux-x86_64.AppImage"
elif [ -f "$SCRIPT_DIR/Lowuncher.AppImage" ]; then
    APPIMAGE_SOURCE="$SCRIPT_DIR/Lowuncher.AppImage"
elif [ -f "/mnt/games/lowuncher/dist/Lowuncher-1.0.0-linux-x86_64.AppImage" ]; then
    APPIMAGE_SOURCE="/mnt/games/lowuncher/dist/Lowuncher-1.0.0-linux-x86_64.AppImage"
fi

echo -e "${CYAN}▶ Копирование файлов лаунчера в $INSTALL_DIR...${RESET}"

if [ -n "$APPIMAGE_SOURCE" ] && [ -f "$APPIMAGE_SOURCE" ]; then
    cp "$APPIMAGE_SOURCE" "$INSTALL_DIR/Lowuncher.AppImage"
    chmod +x "$INSTALL_DIR/Lowuncher.AppImage"
    echo -e "${GREEN}✓ Исполняемый файл Lowuncher.AppImage успешно скопирован.${RESET}"
else
    # Fallback wrapper if launched in development environment
    cat << 'EOF' > "$INSTALL_DIR/lowuncher-runner.sh"
#!/usr/bin/env bash
if command -v npm &>/dev/null && [ -d "/mnt/games/lowuncher" ]; then
    exec npm start --prefix /mnt/games/lowuncher "$@"
else
    echo "Ошибка: Lowuncher AppImage не найден."
    exit 1
fi
EOF
    chmod +x "$INSTALL_DIR/lowuncher-runner.sh"
    echo -e "${YELLOW}ℹ Установлен раннер режима разработки.${RESET}"
fi

# 2. Install High-Quality Icons
echo -e "${CYAN}▶ Установка иконок приложения...${RESET}"
SOURCE_ICON=""
if [ -f "$SCRIPT_DIR/build/icon.png" ]; then
    SOURCE_ICON="$SCRIPT_DIR/build/icon.png"
elif [ -f "$SCRIPT_DIR/icon.png" ]; then
    SOURCE_ICON="$SCRIPT_DIR/icon.png"
elif [ -f "/mnt/games/lowuncher/build/icon.png" ]; then
    SOURCE_ICON="/mnt/games/lowuncher/build/icon.png"
fi

if [ -n "$SOURCE_ICON" ] && [ -f "$SOURCE_ICON" ]; then
    cp "$SOURCE_ICON" "$INSTALL_DIR/icon.png"
    
    # Install into all standard icon themes
    for sz in 16 24 32 48 64 128 256 512; do
        SZ_DIR="$ICON_DIR/${sz}x${sz}/apps"
        mkdir -p "$SZ_DIR"
        if [ -f "$SCRIPT_DIR/build/icons/${sz}x${sz}.png" ]; then
            cp "$SCRIPT_DIR/build/icons/${sz}x${sz}.png" "$SZ_DIR/lowuncher.png"
        else
            cp "$SOURCE_ICON" "$SZ_DIR/lowuncher.png"
        fi
    done
    if command -v gtk-update-icon-cache &>/dev/null; then
        gtk-update-icon-cache -f -t "$ICON_DIR" 2>/dev/null || true
    fi
    echo -e "${GREEN}✓ Системные иконки установлены.${RESET}"
fi

# 3. Create Terminal command symlink
TARGET_EXEC="$INSTALL_DIR/Lowuncher.AppImage"
if [ ! -f "$TARGET_EXEC" ] && [ -f "$INSTALL_DIR/lowuncher-runner.sh" ]; then
    TARGET_EXEC="$INSTALL_DIR/lowuncher-runner.sh"
fi

cat << EOF > "$BIN_DIR/lowuncher"
#!/usr/bin/env bash
exec "$TARGET_EXEC" "\$@"
EOF
chmod +x "$BIN_DIR/lowuncher"
echo -e "${GREEN}✓ Команда 'lowuncher' добавлена в $BIN_DIR/lowuncher.${RESET}"

# 4. Create Desktop Entry (.desktop)
echo -e "${CYAN}▶ Создание ярлыка в меню приложений...${RESET}"
cat << EOF > "$DESKTOP_DIR/lowuncher.desktop"
[Desktop Entry]
Version=1.0
Type=Application
Name=Lowuncher
GenericName=Minecraft Launcher
Comment=Официальный лаунчер школьного сервера Minecraft
Exec=$TARGET_EXEC %U
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
echo -e "  • ${BOLD}Меню приложений:${RESET} найдите 'Lowuncher' в меню вашей системы"
echo -e "  • ${BOLD}Консоль:${RESET} введите команду '${CYAN}lowuncher${RESET}'"
echo -e "  • ${BOLD}Путь программы:${RESET} $INSTALL_DIR/Lowuncher.AppImage"
echo ""

# Prompt to launch
if [ -t 0 ] && [ "$1" != "--no-launch" ]; then
    read -p "Запустить Lowuncher прямо сейчас? [Y/n]: " ans
    if [[ "$ans" =~ ^[YyДд]$ || -z "$ans" ]]; then
        echo -e "${CYAN}Запуск Lowuncher...${RESET}"
        nohup "$TARGET_EXEC" >/dev/null 2>&1 &
        disown
    fi
fi
