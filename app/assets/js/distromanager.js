const fs = require('fs-extra')
const path = require('path')
const { DistributionAPI } = require('helios-core/common')

const ConfigManager = require('./configmanager')

const REMOTE_DISTRO_URL = 'https://raw.githubusercontent.com/bublg95nelox-max/supreme/main/distribution.json'

const SUPREME_DISTRO = {
    version: '1.0.12',
    rss: '',
    servers: [
        {
            id: 'Lowuncher-School',
            name: 'Supreme (1.21.1 NeoForge)',
            description: 'Сборка Supreme: Minecraft 1.21.1 + NeoForge 21.1.265',
            icon: 'assets/images/SealCircle.png',
            version: '1.0.12',
            address: '89.248.236.145:27160',
            backupAddress: 'ozdangalla.netmc.ru:25565',
            minecraftVersion: '1.21.1',
            loader: 'neoforge',
            loaderVersion: '21.1.265',
            usePythonBridge: true,
            mainServer: true,
            autoconnect: false,
            manifestUrl: 'https://raw.githubusercontent.com/bublg95nelox-max/supreme/main/manifest.json',
            modpackUrl: 'https://github.com/bublg95nelox-max/supreme/releases/download/v1.0.5/modpack-supreme-1.21.1.zip',
            modpackVersion: '1.0.12',
            modules: []
        }
    ]
}

const localDistroPath = path.join(ConfigManager.getLauncherDirectory(), 'distribution.json')

// If local distribution doesn't exist yet, seed it with initial fallback
if (!fs.existsSync(localDistroPath)) {
    try {
        fs.outputJsonSync(localDistroPath, SUPREME_DISTRO)
    } catch (_e) {
        // Non-fatal
    }
}

const api = new DistributionAPI(
    ConfigManager.getLauncherDirectory(),
    null,
    null,
    REMOTE_DISTRO_URL,
    false
)

const originalRefresh = api.refreshDistributionOrFallback.bind(api)
api.refreshDistributionOrFallback = async function() {
    try {
        const distro = await originalRefresh()
        if (distro && distro.getServerById('Lowuncher-School')) {
            return distro
        }
    } catch (_err) {
        // Fallback to local
    }
    try {
        if (!fs.existsSync(localDistroPath)) {
            fs.outputJsonSync(localDistroPath, SUPREME_DISTRO)
        }
        return await api.getDistributionLocalLoadOnly()
    } catch (_err2) {
        return api.distribution
    }
}

exports.REMOTE_DISTRO_URL = REMOTE_DISTRO_URL
exports.SUPREME_DISTRO = SUPREME_DISTRO
exports.DistroAPI = api
