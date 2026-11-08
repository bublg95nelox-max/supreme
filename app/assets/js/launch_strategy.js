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
        let serverPort = 25565
        if (rawServer.address) {
            const parts = rawServer.address.split(':')
            serverHost = parts[0]
            if (parts.length > 1) {
                serverPort = Number.parseInt(parts[1], 10) || 25565
            }
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
            modpackUrl: rawServer.modpackUrl || 'https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.5/modpack-supreme-1.21.1.zip',
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
