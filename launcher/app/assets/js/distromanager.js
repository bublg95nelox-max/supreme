const fs = require('fs-extra')
const path = require('path')
const { DistributionAPI } = require('helios-core/common')

const ConfigManager = require('./configmanager')

const SUPREME_DISTRO = {
    version: '1.0.0',
    rss: '',
    servers: [
        {
            id: 'Lowuncher-School',
            name: 'Supreme (1.21.1 NeoForge)',
            description: 'Сборка Supreme: Minecraft 1.21.1 + NeoForge 21.1.265 (107 модов)',
            icon: 'assets/images/SealCircle.png',
            version: '1.0.0',
            address: 'localhost:25565',
            minecraftVersion: '1.21.1',
            loader: 'neoforge',
            loaderVersion: '21.1.265',
            usePythonBridge: true,
            mainServer: true,
            autoconnect: false,
            modules: []
        }
    ]
}

// Always ensure the ONE AND ONLY assembly is written to local distribution.json
try {
    const localDistroPath = path.join(ConfigManager.getLauncherDirectory(), 'distribution.json')
    fs.outputJsonSync(localDistroPath, SUPREME_DISTRO)
} catch (_e) {
    // Non-fatal
}

const api = new DistributionAPI(
    ConfigManager.getLauncherDirectory(),
    null,
    null,
    null,
    false
)

api.refreshDistributionOrFallback = async function() {
    return api.getDistributionLocalLoadOnly()
}

exports.REMOTE_DISTRO_URL = null
exports.SUPREME_DISTRO = SUPREME_DISTRO
exports.DistroAPI = api
