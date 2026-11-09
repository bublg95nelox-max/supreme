/**
 * Initialize UI functions which depend on internal modules.
 * Loaded after core UI functions are initialized in uicore.js.
 */
// Requirements
var path          = require('path')
var { ipcRenderer } = require('electron')
var remote        = require('@electron/remote')
var $             = require('jquery')
var { LoggerUtil } = require('helios-core')
var Lang          = require('./assets/js/langloader')
var isDev         = require('./assets/js/isdev')
var { Type }      = require('helios-distribution-types')
var loggerBinderAutoUpdater = LoggerUtil.getLogger('AutoUpdater')

var AuthManager   = require('./assets/js/authmanager')
var ConfigManager = require('./assets/js/configmanager')
var { DistroAPI } = require('./assets/js/distromanager')

let rscShouldLoad = false
let fatalStartupError = false
let mainUIShown = false
let startupErrorShown = false

// Mapping of each view to their container IDs.
var VIEWS = {
    landing: '#landingContainer',
    loginOptions: '#loginOptionsContainer',
    login: '#loginContainer',
    settings: '#settingsContainer',
    welcome: '#welcomeContainer',
    waiting: '#waitingContainer'
}

// The currently shown view container.
let currentView
let isViewTransitioning = false

/**
 * Switch launcher views.
 * 
 * @param {string} current The ID of the current view container. 
 * @param {*} next The ID of the next view container.
 * @param {*} currentFadeTime Optional. The fade out time for the current view.
 * @param {*} nextFadeTime Optional. The fade in time for the next view.
 * @param {*} onCurrentFade Optional. Callback function to execute when the current
 * view fades out.
 * @param {*} onNextFade Optional. Callback function to execute when the next view
 * fades in.
 */
function switchView(current, next, currentFadeTime = 500, nextFadeTime = 500, onCurrentFade = () => {}, onNextFade = () => {}){
    if(isViewTransitioning || !current || !next || !$(current).length || !$(next).length) {
        console.warn('Cannot switch view: invalid view selector', { current, next })
        return false
    }
    isViewTransitioning = true
    currentView = next
    document.body.classList.toggle('view-landing', next === VIEWS.landing)
    $(`${current}`).fadeOut(currentFadeTime, async () => {
        try {
            await onCurrentFade()
        } catch(err) {
            console.error('onCurrentFade error', err)
        }
        $(`${next}`).fadeIn(nextFadeTime, async () => {
            try {
                await onNextFade()
                if (typeof window.bindAllWindowControls === 'function') {
                    window.bindAllWindowControls()
                }
            } catch(err) {
                console.error('onNextFade error', err)
            } finally {
                isViewTransitioning = false
            }
        })
    })
    return true
}

/**
 * Get the currently shown view container.
 * 
 * @returns {string} The currently shown view container.
 */
function getCurrentView(){
    return currentView
}

async function showMainUI(data){
    if(mainUIShown) return
    console.log('[Lowuncher] showMainUI started')
    mainUIShown = true

    if(!isDev || fs.existsSync(path.join(__dirname, '..', '..', 'dev-app-update.yml'))){
        loggerBinderAutoUpdater.info('Initializing..')
        ipcRenderer.send('autoUpdateAction', 'initAutoUpdater', ConfigManager.getAllowPrerelease())
    }

    try {
        await prepareSettings(true)
    } catch(err) {
        console.error('[Lowuncher] prepareSettings error:', err)
    }

    if (!data || typeof data.getServerById !== 'function') {
        mainUIShown = false
        showFatalStartupError()
        return
    }

    try {
        const selectedServer = data.getServerById(ConfigManager.getSelectedServer()) || (data.getMainServer ? data.getMainServer() : (data.servers && data.servers[0]))
        updateSelectedServer(selectedServer)
    } catch(err) {
        console.error('[Lowuncher] updateSelectedServer error:', err)
    }

    try {
        refreshServerStatus()
    } catch(err) {
        console.error('[Lowuncher] refreshServerStatus error:', err)
    }

    setTimeout(() => {
        const frameBar = document.getElementById('frameBar')
        if (frameBar) frameBar.style.backgroundColor = 'rgba(0, 0, 0, 0.5)'
        const bkid = document.body.getAttribute('bkid') || '0'
        document.body.style.backgroundImage = `url('assets/images/backgrounds/${bkid}.jpg')`
        $('#main').show()

        const isLoggedIn = Object.keys(ConfigManager.getAuthAccounts()).length > 0

        // If this is enabled in a development environment we'll get ratelimited.
        // The relaunch frequency is usually far too high.
        if(!isDev && isLoggedIn){
            try {
                validateSelectedAccount()
            } catch(err) {
                console.error('[Lowuncher] validateSelectedAccount error:', err)
            }
        }

        if(ConfigManager.isFirstLaunch()){
            currentView = VIEWS.welcome
            $(VIEWS.welcome).fadeIn(1000)
        } else {
            if(isLoggedIn){
                currentView = VIEWS.landing
                document.body.classList.add('view-landing')
                $(VIEWS.landing).fadeIn(1000, () => {
                    if (typeof window.bindAllWindowControls === 'function') {
                        window.bindAllWindowControls()
                    }
                })
            } else {
                loginOptionsCancelEnabled(false)
                loginOptionsViewOnLoginSuccess = VIEWS.landing
                loginOptionsViewOnLoginCancel = VIEWS.loginOptions
                currentView = VIEWS.loginOptions
                $(VIEWS.loginOptions).fadeIn(1000)
            }
        }

        setTimeout(() => {
            // Keep the splash animation alive until the loading layer is fully hidden.
            $('#loadingContainer').fadeOut(500)
        }, 250)
        
    }, 750)
    // Disable tabbing to the news container.
    try {
        initNews().then(() => {
            $('#newsContainer *').attr('tabindex', '-1')
        }).catch(err => {
            console.error('[Lowuncher] initNews error:', err)
        })
    } catch(err) {
        console.error('[Lowuncher] initNews invoke error:', err)
    }
}

function showFatalStartupError(){
    if (startupErrorShown) return
    startupErrorShown = true
    $('#loadingContainer').stop(true, true).fadeOut(250, () => {
        const overlay = document.getElementById('overlayContainer')
        if (overlay) overlay.style.background = 'none'
        setOverlayContent(
            Lang.queryJS('uibinder.startup.fatalErrorTitle'),
            Lang.queryJS('uibinder.startup.fatalErrorMessage'),
            Lang.queryJS('uibinder.startup.closeButton')
        )
        setOverlayHandler(() => remote.getCurrentWindow().close())
        toggleOverlay(true)
    })
    return
}

/**
 * Common functions to perform after refreshing the distro index.
 * 
 * @param {Object} data The distro index object.
 */
function onDistroRefresh(data){
    updateSelectedServer(data.getServerById(ConfigManager.getSelectedServer()))
    refreshServerStatus()
    initNews()
    syncModConfigurations(data)
    ensureJavaSettings(data)
}

/**
 * Sync the mod configurations with the distro index.
 * 
 * @param {Object} data The distro index object.
 */
function syncModConfigurations(data){

    const syncedCfgs = []

    for(let serv of data.servers){

        const id = serv.rawServer.id
        const mdls = serv.modules
        const cfg = ConfigManager.getModConfiguration(id)

        if(cfg != null){

            const modsOld = cfg.mods
            const mods = {}

            for(let mdl of mdls){
                const type = mdl.rawModule.type

                if(type === Type.ForgeMod || type === Type.LiteMod || type === Type.LiteLoader || type === Type.FabricMod){
                    if(!mdl.getRequired().value){
                        const mdlID = mdl.getVersionlessMavenIdentifier()
                        if(modsOld[mdlID] == null){
                            mods[mdlID] = scanOptionalSubModules(mdl.subModules, mdl)
                        } else {
                            mods[mdlID] = mergeModConfiguration(modsOld[mdlID], scanOptionalSubModules(mdl.subModules, mdl), false)
                        }
                    } else {
                        if(mdl.subModules.length > 0){
                            const mdlID = mdl.getVersionlessMavenIdentifier()
                            const v = scanOptionalSubModules(mdl.subModules, mdl)
                            if(typeof v === 'object'){
                                if(modsOld[mdlID] == null){
                                    mods[mdlID] = v
                                } else {
                                    mods[mdlID] = mergeModConfiguration(modsOld[mdlID], v, true)
                                }
                            }
                        }
                    }
                }
            }

            syncedCfgs.push({
                id,
                mods
            })

        } else {

            const mods = {}

            for(let mdl of mdls){
                const type = mdl.rawModule.type
                if(type === Type.ForgeMod || type === Type.LiteMod || type === Type.LiteLoader || type === Type.FabricMod){
                    if(!mdl.getRequired().value){
                        mods[mdl.getVersionlessMavenIdentifier()] = scanOptionalSubModules(mdl.subModules, mdl)
                    } else {
                        if(mdl.subModules.length > 0){
                            const v = scanOptionalSubModules(mdl.subModules, mdl)
                            if(typeof v === 'object'){
                                mods[mdl.getVersionlessMavenIdentifier()] = v
                            }
                        }
                    }
                }
            }

            syncedCfgs.push({
                id,
                mods
            })

        }
    }

    ConfigManager.setModConfigurations(syncedCfgs)
    ConfigManager.save()
}

/**
 * Ensure java configurations are present for the available servers.
 * 
 * @param {Object} data The distro index object.
 */
function ensureJavaSettings(data) {
    if(!data || !data.servers) {
        return
    }
    // Nothing too fancy for now.
    for(const serv of data.servers){
        ConfigManager.ensureJavaConfig(serv.rawServer.id, serv.effectiveJavaOptions, serv.rawServer.javaOptions?.ram)
    }

    ConfigManager.save()
}

/**
 * Recursively scan for optional sub modules. If none are found,
 * this function returns a boolean. If optional sub modules do exist,
 * a recursive configuration object is returned.
 * 
 * @returns {boolean | Object} The resolved mod configuration.
 */
function scanOptionalSubModules(mdls, origin){
    if(mdls != null){
        const mods = {}

        for(let mdl of mdls){
            const type = mdl.rawModule.type
            // Optional types.
            if(type === Type.ForgeMod || type === Type.LiteMod || type === Type.LiteLoader || type === Type.FabricMod){
                // It is optional.
                if(!mdl.getRequired().value){
                    mods[mdl.getVersionlessMavenIdentifier()] = scanOptionalSubModules(mdl.subModules, mdl)
                } else {
                    if(mdl.hasSubModules()){
                        const v = scanOptionalSubModules(mdl.subModules, mdl)
                        if(typeof v === 'object'){
                            mods[mdl.getVersionlessMavenIdentifier()] = v
                        }
                    }
                }
            }
        }

        if(Object.keys(mods).length > 0){
            const ret = {
                mods
            }
            if(!origin.getRequired().value){
                ret.value = origin.getRequired().def
            }
            return ret
        }
    }
    return origin.getRequired().def
}

/**
 * Recursively merge an old configuration into a new configuration.
 * 
 * @param {boolean | Object} o The old configuration value.
 * @param {boolean | Object} n The new configuration value.
 * @param {boolean} nReq If the new value is a required mod.
 * 
 * @returns {boolean | Object} The merged configuration.
 */
function mergeModConfiguration(o, n, nReq = false){
    if(typeof o === 'boolean'){
        if(typeof n === 'boolean') return o
        else if(typeof n === 'object'){
            if(!nReq){
                n.value = o
            }
            return n
        }
    } else if(typeof o === 'object'){
        if(typeof n === 'boolean') return typeof o.value !== 'undefined' ? o.value : true
        else if(typeof n === 'object'){
            if(!nReq){
                n.value = typeof o.value !== 'undefined' ? o.value : true
            }

            const newMods = Object.keys(n.mods)
            for(let i=0; i<newMods.length; i++){

                const mod = newMods[i]
                if(o.mods[mod] != null){
                    n.mods[mod] = mergeModConfiguration(o.mods[mod], n.mods[mod])
                }
            }

            return n
        }
    }
    // If for some reason we haven't been able to merge,
    // wipe the old value and use the new one. Just to be safe
    return n
}

async function validateSelectedAccount(){
    const selectedAcc = ConfigManager.getSelectedAccount()
    if(selectedAcc != null){
        const val = await AuthManager.validateSelected()
        if(!val){
            ConfigManager.removeAuthAccount(selectedAcc.uuid)
            ConfigManager.save()
            const accLen = Object.keys(ConfigManager.getAuthAccounts()).length
            setOverlayContent(
                Lang.queryJS('uibinder.validateAccount.failedMessageTitle'),
                accLen > 0
                    ? Lang.queryJS('uibinder.validateAccount.failedMessage', { 'account': selectedAcc.displayName })
                    : Lang.queryJS('uibinder.validateAccount.failedMessageSelectAnotherAccount', { 'account': selectedAcc.displayName }),
                Lang.queryJS('uibinder.validateAccount.loginButton'),
                Lang.queryJS('uibinder.validateAccount.selectAnotherAccountButton')
            )
            setOverlayHandler(() => {

                const isMicrosoft = selectedAcc.type === 'microsoft'

                if(isMicrosoft) {
                    // Empty for now
                } else {
                    // Mojang
                    // For convenience, pre-populate the username of the account.
                    document.getElementById('loginUsername').value = selectedAcc.username
                    validateEmail(selectedAcc.username)
                }
                
                loginOptionsViewOnLoginSuccess = getCurrentView()
                loginOptionsViewOnLoginCancel = VIEWS.loginOptions

                if(accLen > 0) {
                    loginOptionsViewOnCancel = getCurrentView()
                    loginOptionsViewCancelHandler = () => {
                        if(isMicrosoft) {
                            ConfigManager.addMicrosoftAuthAccount(
                                selectedAcc.uuid,
                                selectedAcc.accessToken,
                                selectedAcc.username,
                                selectedAcc.expiresAt,
                                selectedAcc.microsoft.access_token,
                                selectedAcc.microsoft.refresh_token,
                                selectedAcc.microsoft.expires_at
                            )
                        } else {
                            ConfigManager.addMojangAuthAccount(selectedAcc.uuid, selectedAcc.accessToken, selectedAcc.username, selectedAcc.displayName)
                        }
                        ConfigManager.save()
                        validateSelectedAccount()
                    }
                    loginOptionsCancelEnabled(true)
                } else {
                    loginOptionsCancelEnabled(false)
                }
                toggleOverlay(false)
                switchView(getCurrentView(), VIEWS.loginOptions)
            })
            setDismissHandler(() => {
                if(accLen > 1){
                    prepareAccountSelectionList()
                    $('#overlayContent').fadeOut(250, () => {
                        bindOverlayKeys(true, 'accountSelectContent', true)
                        $('#accountSelectContent').fadeIn(250)
                    })
                } else {
                    const accountsObj = ConfigManager.getAuthAccounts()
                    const accounts = Array.from(Object.keys(accountsObj), v => accountsObj[v])
                    // This function validates the account switch.
                    setSelectedAccount(accounts[0].uuid)
                    toggleOverlay(false)
                }
            })
            toggleOverlay(true, accLen > 0)
        } else {
            return true
        }
    } else {
        return true
    }
}

/**
 * Temporary function to update the selected account along
 * with the relevent UI elements.
 * 
 * @param {string} uuid The UUID of the account.
 */
function setSelectedAccount(uuid){
    const authAcc = ConfigManager.setSelectedAccount(uuid)
    ConfigManager.save()
    updateSelectedAccount(authAcc)
    validateSelectedAccount()
}

let distroHandlingStarted = false

async function handleDistributionReady(res) {
    if (distroHandlingStarted || mainUIShown) return
    distroHandlingStarted = true
    console.log('[Lowuncher] handleDistributionReady called with res =', res)
    if(res) {
        let data
        try {
            data = await DistroAPI.getDistribution()
        } catch (err) {
            console.error('Unable to load distribution', err)
            fatalStartupError = true
            showFatalStartupError()
            return
        }
        if (!data) {
            fatalStartupError = true
            showFatalStartupError()
            return
        }
        try {
            syncModConfigurations(data)
            ensureJavaSettings(data)
        } catch(e) {
            console.warn('[Lowuncher] Non-fatal syncModConfigurations error:', e)
        }
        await showMainUI(data)
    } else {
        fatalStartupError = true
        showFatalStartupError()
    }
}

function checkStartupDistribution() {
    if (mainUIShown || distroHandlingStarted) return

    if (typeof globalThis !== 'undefined' && globalThis.__distributionIndexDone !== undefined) {
        console.log('[Lowuncher] Using preloader __distributionIndexDone flag:', globalThis.__distributionIndexDone)
        handleDistributionReady(globalThis.__distributionIndexDone)
        return
    }

    if (rscShouldLoad) {
        rscShouldLoad = false
        console.log('[Lowuncher] Using rscShouldLoad')
        handleDistributionReady(true)
        return
    }

    // Ping main process in case preloader sent it earlier
    ipcRenderer.send('requestDistributionIndexDone')
}

// Synchronous Listener
document.addEventListener('readystatechange', async () => {
    if (document.readyState === 'interactive' || document.readyState === 'complete'){
        checkStartupDistribution()
    }
}, false)

document.addEventListener('DOMContentLoaded', () => {
    checkStartupDistribution()
})

// Actions that must be performed after the distribution index is downloaded.
ipcRenderer.on('distributionIndexDone', async (event, res) => {
    console.log('[Lowuncher] Received distributionIndexDone IPC event:', res)
    if (document.readyState === 'interactive' || document.readyState === 'complete'){
        await handleDistributionReady(res)
    } else {
        rscShouldLoad = true
    }
})

// Failsafe timer: if nothing happened within 1.5s after script load, load directly!
setTimeout(async () => {
    if (!mainUIShown && !fatalStartupError && !distroHandlingStarted) {
        console.warn('[Lowuncher] Failsafe timer: distribution index not received, attempting direct local load...')
        try {
            const data = await DistroAPI.getDistributionLocalLoadOnly()
            if (data) {
                await handleDistributionReady(true)
            } else {
                showFatalStartupError()
            }
        } catch (err) {
            console.error('[Lowuncher] Failsafe direct local load failed, trying getDistribution:', err)
            try {
                const data = await DistroAPI.getDistribution()
                if (data) {
                    await handleDistributionReady(true)
                } else {
                    showFatalStartupError()
                }
            } catch (err2) {
                console.error('[Lowuncher] Failsafe getDistribution failed:', err2)
                showFatalStartupError()
            }
        }
    }
}, 1500)

// Util for development
async function devModeToggle() {
    DistroAPI.toggleDevMode(true)
    const data = await DistroAPI.refreshDistributionOrFallback()
    ensureJavaSettings(data)
    updateSelectedServer(data.servers[0])
    syncModConfigurations(data)
}
