# Lowuncher 🚀

**Lowuncher** — кастомный Minecraft-лаунчер на базе **HeliosLauncher (Electron)** с интегрированным **Python-движком (`minecraft-launcher-lib`)** для поддержки Minecraft 1.21.1, NeoForge и офлайн-авторизации.

Разработан специально для школьного сообщества: поддержка Windows и Arch Linux, офлайн-вход по никнейму, автоскачивание Java 21, синхронизация модов и изоляция директории.

---

## ✨ Ключевые возможности

- 🖥️ **Современный интерфейс**: Построен на базе HeliosLauncher (Electron / HTML / EJS). Плавные анимации, статус игровых серверов, детальные настройки и консоль.
- 🎮 **Minecraft 1.21.1 + NeoForge**: Нативная установка и запуск NeoForge через модуль [`minecraft-launcher-lib`](https://gitlab.com/JakobDev/minecraft-launcher-lib).
- 👤 **Двойная авторизация**:
  - **Офлайн по никнейму**: вход без учетной записи Microsoft (с детерминированным UUID v3 MD5 и скинами).
  - **Microsoft OAuth2**: для игроков с лицензией.
- ☕ **Автоскачивание Java 21**: Автоматическая установка изолированной Eclipse Temurin JRE 21 (через Adoptium API).
- ⚡ **Умная пофайловая синхронизация**: Сверка контрольных сумм SHA-256 модов и конфигов через `manifest.json` и бесплатная раздача через GitHub Releases.
- 🧠 **Оптимизация памяти (RAM)**: По умолчанию выделяет 50% от системной памяти (до разумного максимума в 12 ГБ).
- 🛡️ **Изолированное окружение**: Все данные лаунчера и игры хранятся в `%APPDATA%/.lowuncher` (Windows) и `~/.lowuncher` (Linux), не конфликтуя с личным `.minecraft` или TLauncher.
- 🎯 **Поддержка платформ**: Только **Windows (x64)** и **Linux (Arch Linux)**.

---

## 📁 Структура проекта

```text
lowuncher/
├── app/                           # Фронтенд HeliosLauncher (Electron)
│   ├── assets/
│   │   ├── css/                  # Стили интерфейса
│   │   └── js/
│   │       ├── authmanager.js    # Менеджер аккаунтов (Offline UUID + Microsoft)
│   │       ├── configmanager.js  # Настройки (.lowuncher, RAM 50% max 12GB)
│   │       ├── python_bridge.js  # Мост между Electron и Python-ядром
│   │       ├── distromanager.js  # Менеджер списка серверов
│   │       └── scripts/          # Контроллеры (landing, settings, login)
│   ├── loginOptions.ejs          # Меню выбора способа входа (Офлайн / Microsoft)
│   ├── login.ejs                 # Форма ввода никнейма
│   └── app.ejs                   # Главный каркас окна
│
├── core/                          # Python-дополнение (minecraft-launcher-lib)
│   ├── __init__.py
│   ├── bridge_cli.py             # CLI-точка входа для Electron
│   ├── installer.py              # Скачивание Minecraft 1.21.1 и установка NeoForge
│   ├── launcher.py               # Генерация параметров JVM и запуск процесса
│   ├── sync_engine.py            # Пофайловая сверка SHA-256 и загрузка модов
│   ├── java_manager.py           # Менеджер изолированной Java 21 (Adoptium)
│   └── auth.py                   # Генерация стабильного офлайн-UUID
│
├── tools/                         # Инструменты администратора
│   └── generate_manifest.py      # Генератор manifest.json и SHA-256 для GitHub Releases
│
├── tests/                         # Тесты
│   ├── test_helios_mods.js       # Тесты JavaScript-модификаций (UUID, RAM, мост)
│   ├── test_bridge.py            # Тесты Python-моста
│   └── test_sync.py              # Тесты синхронизации
│
├── electron-builder.yml           # Конфигурация сборщика под Windows и Linux
├── package.json                   # Зависимости Electron / Node.js
├── requirements.txt               # Зависимости Python
└── index.js                       # Главный процесс Electron
```

---

## 🚀 Быстрый запуск для разработки

### 1. Установка зависимостей

```bash
# 1. Зависимости Python
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 2. Зависимости Node.js / Electron
npm install
```

### 2. Запуск тестов

```bash
npm test
```

### 3. Запуск лаунчера

```bash
npm start
```

---

## 📦 Сборка дистрибутива

### Сборка под Linux (Arch Linux / AppImage)
```bash
npm run dist:linux
```
Готовый переносимый файл появится в `dist/Lowuncher-1.0.0-linux-x64.AppImage`.

### Сборка под Windows (NSIS-установщик и Portable .exe)
```bash
npm run dist:win
```
Готовые сборки появятся в папке `dist/`.

---

## 🛠️ Инструкция администратора: Как обновить сборку модов

1. Поместите обновленные моды в папку `my_modpack/mods/`, а конфиги в `my_modpack/config/`.
2. Запустите генератор манифеста:
   ```bash
   python tools/generate_manifest.py /путь/к/my_modpack \
       --output manifest.json \
       --base-url "https://github.com/<ваш-профиль>/<репозиторий>/releases/latest/download" \
       --mc-version 1.21.1 \
       --neoforge-version 21.1.72
   ```
3. Опубликуйте `manifest.json` и `.jar` файлы модов в GitHub Releases.
4. При запуске игроки автоматически получат обновленные файлы за несколько секунд!
