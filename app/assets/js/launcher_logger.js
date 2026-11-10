const fs = require('fs-extra')
const path = require('path')
const os = require('os')

let ConfigManager = null
function getConfigManager() {
    if (!ConfigManager) {
        try {
            ConfigManager = require('./configmanager')
        } catch (_e) {}
    }
    return ConfigManager
}

class LauncherLogger {
    static initialized = false
    static logBuffer = []

    /**
     * Sanitizes tokens, passwords, and sensitive information from log lines.
     * @param {string} text 
     * @returns {string}
     */
    static sanitize(text) {
        if (!text || typeof text !== 'string') return ''
        return text
            .replace(/(--token\s+)[^\s]+/gi, '$1***')
            .replace(/(MINECRAFT_AUTH_TOKEN\s*[:=]\s*)[^\s]+/gi, '$1***')
            .replace(/(token\s*[:=]\s*["']?)[a-f0-9_\-]+(["']?)/gi, '$1***$2')
            .replace(/(password\s*[:=]\s*["']?)[^\s"']+(["']?)/gi, '$1***$2')
            .replace(/(api[_-]?key\s*[:=]\s*["']?)[^\s"']+(["']?)/gi, '$1***$2')
    }

    /**
     * Resolves the canonical path to the launcher.log file.
     * @returns {string}
     */
    static getLogPath() {
        try {
            const cfg = getConfigManager()
            if (cfg && typeof cfg.getDataDirectory === 'function') {
                const dir = cfg.getDataDirectory()
                if (dir) return path.join(dir, 'launcher.log')
            }
        } catch (_e) {}

        const appData = process.env.APPDATA || (
            process.platform === 'darwin'
                ? path.join(os.homedir(), 'Library', 'Application Support')
                : path.join(os.homedir(), '.local', 'share')
        )
        return path.join(appData, 'lowuncher', 'launcher.log')
    }

    static formatTimestamp() {
        const d = new Date()
        return d.toISOString().replace('T', ' ').replace('Z', '')
    }

    /**
     * Initializes the log file, rotates if exceeding 5MB, and writes session header.
     */
    static init() {
        if (LauncherLogger.initialized) return
        LauncherLogger.initialized = true
        const logPath = LauncherLogger.getLogPath()
        try {
            fs.ensureDirSync(path.dirname(logPath))
            // Check file size, truncate if > 5 MB
            if (fs.existsSync(logPath)) {
                const stat = fs.statSync(logPath)
                if (stat.size > 5 * 1024 * 1024) {
                    const content = fs.readFileSync(logPath, 'utf8')
                    const lines = content.split('\n')
                    const trimmed = lines.slice(-2000).join('\n')
                    fs.writeFileSync(logPath, `[LOG ROTATED - PRESERVING LAST 2000 LINES]\n${trimmed}\n`, 'utf8')
                }
            }

            const totalRamGB = Math.round(os.totalmem() / (1024 * 1024 * 1024))
            const freeRamGB = Math.round(os.freemem() / (1024 * 1024 * 1024))
            const header = [
                '\n' + '='.repeat(60),
                `[${LauncherLogger.formatTimestamp()}] LOWUNCHER SESSION STARTED`,
                `Version: 1.0.6 | Node: ${process.version} | Electron: ${process.versions ? process.versions.electron : 'N/A'}`,
                `OS: ${os.type()} ${os.release()} (${os.arch()})`,
                `RAM: ${totalRamGB} GB total, ~${freeRamGB} GB free`,
                `Log file: ${logPath}`,
                '='.repeat(60) + '\n'
            ].join('\n')

            fs.appendFileSync(logPath, header, { encoding: 'utf8' })
        } catch (e) {
            console.warn('[LauncherLogger] Failed to initialize log file:', e)
        }
    }

    /**
     * Appends a log line synchronously to launcher.log and buffer.
     * @param {string} level 
     * @param {string} message 
     */
    static write(level, message) {
        if (!LauncherLogger.initialized) {
            LauncherLogger.init()
        }
        const sanitizedMsg = LauncherLogger.sanitize(String(message))
        const timestamp = LauncherLogger.formatTimestamp()
        const line = `[${timestamp}] [${level}] ${sanitizedMsg}`

        // Keep in memory buffer
        LauncherLogger.logBuffer.push(line)
        if (LauncherLogger.logBuffer.length > 500) {
            LauncherLogger.logBuffer.shift()
        }

        const logPath = LauncherLogger.getLogPath()
        try {
            fs.appendFileSync(logPath, line + '\n', { encoding: 'utf8' })
        } catch (_err) {}
    }

    static info(msg) { LauncherLogger.write('INFO', msg) }
    static log(msg) { LauncherLogger.write('INFO', msg) }
    static warn(msg) { LauncherLogger.write('WARN', msg) }
    static error(msg, err) {
        let text = String(msg)
        if (err) {
            text += `\n${err.stack || err.message || err}`
        }
        LauncherLogger.write('ERROR', text)
    }

    /**
     * Reads recent log lines from disk, falling back to memory buffer.
     * @param {number} maxLines 
     * @returns {string[]}
     */
    static getRecentLogs(maxLines = 500) {
        const logPath = LauncherLogger.getLogPath()
        try {
            if (fs.existsSync(logPath)) {
                const content = fs.readFileSync(logPath, 'utf8')
                const lines = content.split('\n').filter(l => Boolean(l.trim()))
                return lines.slice(-maxLines)
            }
        } catch (_e) {}
        return LauncherLogger.logBuffer.slice(-maxLines)
    }
}

module.exports = LauncherLogger
