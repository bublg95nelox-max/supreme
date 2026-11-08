const {ipcRenderer}  = require('electron')
const fs             = require('fs-extra')
const os             = require('os')
const path           = require('path')

const ConfigManager  = require('./configmanager')
const { DistroAPI }  = require('./distromanager')
const LangLoader     = require('./langloader')
const { LoggerUtil } = require('helios-core')
// eslint-disable-next-line no-unused-vars
const { HeliosDistribution } = require('helios-core/common')

const logger = LoggerUtil.getLogger('Preloader')

logger.info('Loading..')

// Load ConfigManager
ConfigManager.load()

// Yuck!
// TODO Fix this
DistroAPI['commonDir'] = ConfigManager.getCommonDirectory()
DistroAPI['instanceDir'] = ConfigManager.getInstanceDirectory()

// Load Strings
LangLoader.setupLanguage()

/**
 * 
 * @param {HeliosDistribution} data 
 */
function onDistroLoad(data){
    ConfigManager.setSelectedServer('Lowuncher-School')
    ConfigManager.save()
    if (typeof globalThis !== 'undefined') {
        globalThis.__distributionIndexDone = (data != null)
        globalThis.__distributionData = data
    }
    ipcRenderer.send('distributionIndexDone', data != null)
}

// Ensure Distribution is loaded from local disk. Never leave the splash waiting forever.
const distributionTimeout = new Promise((_, reject) => {
    setTimeout(() => reject(new Error('Distribution loading timed out after 15 seconds')), 15000)
})

const cachedDistribution = DistroAPI.getDistributionLocalLoadOnly()
Promise.race([cachedDistribution, distributionTimeout])
    .then(heliosDistro => {
        logger.info('Loaded Supreme distribution index.')
        console.log('[Lowuncher] Supreme distribution loaded; opening main UI.')
        onDistroLoad(heliosDistro)
    })
    .catch(err => {
        logger.info('Failed to load distribution index within startup timeout.')
        logger.error(err)
        onDistroLoad(null)
    })

// Clean up temp dir incase previous launches ended unexpectedly. 
fs.remove(path.join(os.tmpdir(), ConfigManager.getTempNativeFolder()), (err) => {
    if(err){
        logger.warn('Error while cleaning natives directory', err)
    } else {
        logger.info('Cleaned natives directory.')
    }
})