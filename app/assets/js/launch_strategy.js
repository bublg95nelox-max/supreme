const path = require('path')
const PythonBridge = require('./python_bridge')
const { LoggerUtil } = require('helios-core')

const logger = LoggerUtil.getLogger('LaunchStrategy')

let ConfigManager = null
function getConfigManager() {
    if (!ConfigManager) {
        ConfigManager = require('./configmanager')
    }
    return ConfigManager
}

class LaunchStrategy {

    /**
     * Resolves RAM in MB configured for the given server.
     * 
     * @param {string} serverId 
     * @returns {number} RAM in MB
     */
    static resolveServerRAM(serverId) {
        let ramMB = 4096
        try {
            const cfg = getConfigManager()
            const rawRam = cfg.getMaxRAM(serverId)
            if (rawRam && rawRam.endsWith('G')) {
                ramMB = Number.parseInt(rawRam, 10) * 1024
            } else if (rawRam && rawRam.endsWith('M')) {
                ramMB = Number.parseInt(rawRam, 10)
            }
        } catch (_e) {
            // fallback
        }
        return ramMB
    }

    /**
     * Parses server address into host and port, correctly handling IPv4, IPv6, and hostnames.
     * 
     * @param {string} address e.g. "mc.example.com", "mc.example.com:25565", "[2001:db8::1]:25565", "[::1]"
     * @returns {{ host: string|null, port: number|null }}
     */
    static parseServerAddress(address) {
        if (!address || typeof address !== 'string') {
            return { host: null, port: null }
        }
        const trimmed = address.trim()
        if (!trimmed) {
            return { host: null, port: null }
        }

        // Check for bracketed IPv6: [host]:port or [host]
        const ipv6BracketMatch = trimmed.match(/^\[([a-fA-F0-9:]+)\](?::(\d+))?$/)
        if (ipv6BracketMatch) {
            const host = ipv6BracketMatch[1]
            const port = ipv6BracketMatch[2] ? Number.parseInt(ipv6BracketMatch[2], 10) : 25565
            return { host, port: (port >= 1 && port <= 65535) ? port : 25565 }
        }

        // Count colons. If more than 1 colon and not bracketed, it's a bare IPv6 address without port
        const colonCount = (trimmed.match(/:/g) || []).length
        if (colonCount > 1) {
            return { host: trimmed, port: 25565 }
        }

        if (colonCount === 1) {
            const [host, rawPort] = trimmed.split(':')
            const parsedPort = Number.parseInt(rawPort, 10)
            const port = (Number.isInteger(parsedPort) && parsedPort >= 1 && parsedPort <= 65535) ? parsedPort : 25565
            return { host: host || null, port }
        }

        // No colon: host only
        return { host: trimmed, port: 25565 }
    }

    /**
     * Coordinates the game launch using the Python launcher engine.
     * 
     * @param {Object} server The selected distribution server object
     * @param {Object} authUser The selected account object
     * @param {Object} [callbacks] Callbacks for progress and logging
     * @param {Function} [callbacks.onProgress] callback(percent, status)
     * @param {Function} [callbacks.onLog] callback(line)
     * @returns {Promise.<number>} Exit code of launch process
     */
    static async launch(server, authUser, callbacks = {}) {
        const rawServer = server ? (server.rawServer || {}) : {}
        const ramMB = LaunchStrategy.resolveServerRAM(rawServer.id)

        let serverHost = null
        let serverPort = null
        if (rawServer.address) {
            const parsed = LaunchStrategy.parseServerAddress(rawServer.address)
            serverHost = parsed.host
            serverPort = parsed.port
        }

        const cfg = getConfigManager()
        const instanceDir = cfg.getInstanceDirectory ? cfg.getInstanceDirectory() : path.join(process.cwd(), 'instances')
        const gameDir = path.join(instanceDir, rawServer.id || 'default')

        let javaPath = null
        try {
            javaPath = cfg.getJavaExecutable ? cfg.getJavaExecutable(rawServer.id) : null
        } catch (_e) {}

        logger.info(`Coordinating game launch for server: ${rawServer.name || rawServer.id || 'default'}`)

        return await PythonBridge.launchGame({
            gameDir,
            mcVersion: rawServer.minecraftVersion || '1.21.1',
            loader: rawServer.loader || 'neoforge',
            loaderVersion: rawServer.loaderVersion || '21.1.265',
            username: authUser ? authUser.displayName : 'Player',
            uuid: authUser ? authUser.uuid : '',
            token: authUser ? (authUser.accessToken || '0') : '0',
            ram: ramMB,
            manifestUrl: rawServer.manifestUrl || 'https://raw.githubusercontent.com/bublg95nelox-max/supreme/main/manifest.json',
            modpackUrl: rawServer.modpackUrl || 'https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.6/modpack-supreme-1.21.1.zip',
            serverHost,
            serverPort,
            javaPath,
            onProgress: callbacks.onProgress,
            onStarted: callbacks.onStarted,
            onLog: callbacks.onLog
        })
    }
}

module.exports = LaunchStrategy
