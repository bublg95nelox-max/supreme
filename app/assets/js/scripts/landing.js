/**
 * Script for landing.ejs
 */
// Requirements
var { URL }                   = require('url')
var { shell, clipboard }      = require('electron')
var nodeOs                    = require('os')
var nodeCrypto                = require('crypto')
var fsExtra                   = require('fs-extra')
var pathLib                   = require('path')
const {
    MojangRestAPI,
    getServerStatus
}                             = require('helios-core/mojang')
const {
    RestResponseStatus,
    isDisplayableError,
    validateLocalFile
}                             = require('helios-core/common')
const {
    FullRepair,
    DistributionIndexProcessor,
    MojangIndexProcessor,
    downloadFile
}                             = require('helios-core/dl')
const {
    validateSelectedJvm,
    ensureJavaDirIsRoot,
    javaExecFromRoot,
    discoverBestJvmInstallation,
    latestOpenJDK,
    extractJdk
}                             = require('helios-core/java')

// Internal Requirements
const DiscordWrapper          = require('./assets/js/discordwrapper')
const ProcessBuilder          = require('./assets/js/processbuilder')
const LauncherLogger          = require('./assets/js/launcher_logger')

try {
    LauncherLogger.init()
} catch (_initErr) {}

// Launch Elements
const launch_content          = document.getElementById('launch_content')
const launch_details          = document.getElementById('launch_details')
const launch_progress         = document.getElementById('launch_progress')
const launch_progress_label   = document.getElementById('launch_progress_label')
const launch_details_text     = document.getElementById('launch_details_text')
const server_selection_button = document.getElementById('server_selection_button')
const user_text               = document.getElementById('user_text')

const loggerLanding = LoggerUtil.getLogger('Landing')

/* Launch Progress Wrapper Functions */

/**
 * Show/hide the loading area.
 * 
 * @param {boolean} loading True if the loading area should be shown, otherwise false.
 */
function toggleLaunchArea(loading){
    if(loading){
        launch_details.style.display = 'flex'
        launch_content.style.display = 'none'
    } else {
        launch_details.style.display = 'none'
        launch_content.style.display = 'inline-flex'
    }
}

/**
 * Set the details text of the loading area.
 * 
 * @param {string} details The new text for the loading details.
 */
function setLaunchDetails(details){
    launch_details_text.innerHTML = details
}

/**
 * Set the value of the loading progress bar and display that value.
 * 
 * @param {number} percent Percentage (0-100)
 */
function setLaunchPercentage(percent){
    launch_progress.setAttribute('max', 100)
    launch_progress.setAttribute('value', percent)
    launch_progress_label.innerHTML = percent + '%'
}

/**
 * Set the value of the OS progress bar and display that on the UI.
 * 
 * @param {number} percent Percentage (0-100)
 */
function setDownloadPercentage(percent){
    remote.getCurrentWindow().setProgressBar(percent/100)
    setLaunchPercentage(percent)
}

/**
 * Updates UI state of the launch/stop button.
 * @param {boolean} isRunning 
 */
function setGameRunningState(isRunning){
    const launchBtn = document.getElementById('launch_button')
    if(!launchBtn) return
    const textSpan = launchBtn.querySelector('.modernPlayText') || launchBtn

    if(isRunning){
        launchBtn.classList.add('isRunning')
        launchBtn.disabled = false
        if(textSpan) textSpan.innerText = 'ОСТАНОВИТЬ'
        launchBtn.title = 'Принудительно закрыть процесс Minecraft'
    } else {
        launchBtn.classList.remove('isRunning')
        launchBtn.disabled = false
        if(textSpan) textSpan.innerText = 'ИГРАТЬ'
        launchBtn.title = 'Запустить Minecraft'
    }
}

/**
 * Enable or disable the launch button.
 * 
 * @param {boolean} val True to enable, false to disable.
 */
function setLaunchEnabled(val){
    const launchBtn = document.getElementById('launch_button')
    if(launchBtn) launchBtn.disabled = !val
}

let isLaunchInProgress = false

// Bind launch button
document.getElementById('launch_button').addEventListener('click', async e => {
    const PythonBridge = require('./assets/js/python_bridge')
    if(PythonBridge.isGameRunning()){
        loggerLanding.info('Forcibly terminating running Minecraft process by user request...')
        const launchBtn = document.getElementById('launch_button')
        const textSpan = launchBtn.querySelector('.modernPlayText') || launchBtn
        if(textSpan) textSpan.innerText = 'ОСТАНОВКА...'
        launchBtn.disabled = true
        PythonBridge.killGame()
        let checks = 0
        const pollTimer = setInterval(() => {
            checks++
            if (!PythonBridge.isGameRunning() || checks >= 10) {
                clearInterval(pollTimer)
                setGameRunningState(false)
                toggleLaunchArea(false)
                setLaunchEnabled(true)
                isLaunchInProgress = false
            }
        }, 200)
        return
    }

    if (isLaunchInProgress) {
        loggerLanding.warn('Launch already in progress, ignoring duplicate click.')
        return
    }
    isLaunchInProgress = true

    loggerLanding.info('Launching game..')
    setLaunchEnabled(false)
    try {
        const server = (await DistroAPI.getDistribution()).getServerById(ConfigManager.getSelectedServer())
        const isPythonBridge = server?.rawServer?.usePythonBridge || server?.rawServer?.minecraftVersion?.startsWith('1.21') || server?.rawServer?.loader === 'neoforge'

        if(isPythonBridge){
            // Python bridge takes care of Java 21, mod sync, and NeoForge installation!
            await dlAsync()
            return
        }

        const jExe = ConfigManager.getJavaExecutable(ConfigManager.getSelectedServer())
        if(jExe == null){
            await asyncSystemScan(server.effectiveJavaOptions)
        } else {

            setLaunchDetails(Lang.queryJS('landing.launch.pleaseWait'))
            toggleLaunchArea(true)
            setLaunchPercentage(0, 100)

            const details = await validateSelectedJvm(ensureJavaDirIsRoot(jExe), server.effectiveJavaOptions.supported)
            if(details != null){
                loggerLanding.info('Jvm Details', details)
                await dlAsync()

            } else {
                await asyncSystemScan(server.effectiveJavaOptions)
            }
        }
    } catch(err) {
        loggerLanding.error('Unhandled error in during launch process.', err)
        showLaunchFailure(Lang.queryJS('landing.launch.failureTitle'), Lang.queryJS('landing.launch.failureText'), err)
        setLaunchEnabled(true)
        isLaunchInProgress = false
    }
})

// Bind settings button
document.getElementById('settingsMediaButton').onclick = async e => {
    await prepareSettings()
    switchView(getCurrentView(), VIEWS.settings)
}

// Bind avatar overlay button.
document.getElementById('avatarOverlay').onclick = async e => {
    await prepareSettings()
    switchView(getCurrentView(), VIEWS.settings, 500, 500, () => {
        settingsNavItemListener(document.getElementById('settingsNavAccount'), false)
    })
}

// Bind selected account
function updateSelectedAccount(authUser){
    let username = Lang.queryJS('landing.selectedAccount.noAccountSelected')
    let skinKey = 'Steve'
    if(authUser != null){
        if(authUser.displayName != null){
            username = authUser.displayName
        }
        if(authUser.uuid != null){
            const skinDonor = ConfigManager.getAccountSkinDonor(authUser.uuid)
            skinKey = skinDonor || (((authUser.type === 'offline' || authUser.type === 'community') && authUser.displayName) ? authUser.displayName : authUser.uuid)
            const avatarContainer = document.getElementById('avatarContainer')
            if(avatarContainer){
                avatarContainer.style.backgroundImage = `url('https://mc-heads.net/avatar/${encodeURIComponent(skinKey)}/64')`
                avatarContainer.style.backgroundSize = 'cover'
                avatarContainer.style.backgroundPosition = 'center'
            }
            const sidebarSkinHead = document.getElementById('sidebarSkinHead')
            if(sidebarSkinHead){
                sidebarSkinHead.src = `https://mc-heads.net/avatar/${encodeURIComponent(skinKey)}/64`
            }
        }
    }
    if(user_text) user_text.innerHTML = username

    const userTypeLabel = document.getElementById('user_type_label')
    if(userTypeLabel){
        if(authUser != null){
            const skinDonor = ConfigManager.getAccountSkinDonor(authUser.uuid)
            if(skinDonor){
                userTypeLabel.innerText = `Скин: ${skinDonor}`
            } else if(authUser.type === 'offline'){
                userTypeLabel.innerText = 'Офлайн аккаунт'
            } else if(authUser.type === 'community'){
                userTypeLabel.innerText = 'Игровой профиль'
            } else if(authUser.type === 'microsoft'){
                userTypeLabel.innerText = 'Лицензия Microsoft'
            } else {
                userTypeLabel.innerText = 'Игровой профиль'
            }
        } else {
            userTypeLabel.innerText = 'Нет аккаунта'
        }
    }

    // Update Accounts tab elements if present
    const activeAccountAvatar = document.getElementById('activeAccountAvatar')
    const activeAccountName = document.getElementById('activeAccountName')
    const activeAccountType = document.getElementById('activeAccountType')
    if(activeAccountAvatar) activeAccountAvatar.src = `https://mc-heads.net/avatar/${encodeURIComponent(skinKey)}/64`
    if(activeAccountName) activeAccountName.innerText = username
    if(activeAccountType) {
        if(authUser && authUser.type === 'community') activeAccountType.innerText = 'Логин и пароль'
        else if(authUser && authUser.type === 'microsoft') activeAccountType.innerText = 'Лицензия Microsoft'
        else activeAccountType.innerText = 'Офлайн профиль'
    }

    if(typeof updateSkinTabUI === 'function') {
        updateSkinTabUI()
    }
}
updateSelectedAccount(ConfigManager.getSelectedAccount())

// Bind selected server
function updateSelectedServer(serv){
    if(getCurrentView() === VIEWS.settings){
        fullSettingsSave()
    }
    ConfigManager.setSelectedServer(serv != null ? serv.rawServer.id : null)
    ConfigManager.save()
    if(server_selection_button){
        server_selection_button.innerHTML = '&#8226; ' + (serv != null ? serv.rawServer.name : Lang.queryJS('landing.noSelection'))
    }
    const modernServerName = document.getElementById('modernServerName')
    if(modernServerName){
        modernServerName.innerText = serv != null ? serv.rawServer.name : 'Школьный сервер'
    }
    const modernServerMeta = document.getElementById('modernServerMeta')
    if(modernServerMeta){
        modernServerMeta.innerText = 'v1.21.1'
    }

    // Dynamic count of real mods from instance directory
    const statModsVal = document.getElementById('statModsVal')
    if(statModsVal){
        try {
            const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), 'Lowuncher-School', 'mods')
            if (fsExtra.existsSync(instanceDir)) {
                const count = fsExtra.readdirSync(instanceDir).filter(f => f.endsWith('.jar') && !f.endsWith('.disabled')).length
                statModsVal.innerText = `${count} активных`
            } else {
                statModsVal.innerText = '107 активных'
            }
        } catch (e) {
            statModsVal.innerText = '107 активных'
        }
    }

    if(getCurrentView() === VIEWS.settings){
        animateSettingsTabRefresh()
    }
    setLaunchEnabled(serv != null)
}

// Update Mojang Status Color
const refreshMojangStatuses = async function(){
    loggerLanding.info('Refreshing Mojang Statuses..')

    let status = 'grey'
    let tooltipEssentialHTML = ''
    let tooltipNonEssentialHTML = ''
    let statuses

    try {
        const response = await MojangRestAPI.status()
        if(response.responseStatus === RestResponseStatus.SUCCESS) {
            statuses = response.data
        } else {
            loggerLanding.warn('Unable to refresh Mojang service status.')
            statuses = MojangRestAPI.getDefaultStatuses()
        }
    } catch(err) {
        loggerLanding.warn('Unable to refresh Mojang service status (network error).')
        statuses = MojangRestAPI.getDefaultStatuses()
    }
    
    let greenCount = 0
    let greyCount = 0

    for(let i=0; i<statuses.length; i++){
        const service = statuses[i]

        const tooltipHTML = `<div class="mojangStatusContainer">
            <span class="mojangStatusIcon" style="color: ${MojangRestAPI.statusToHex(service.status)};">&#8226;</span>
            <span class="mojangStatusName">${service.name}</span>
        </div>`
        if(service.essential){
            tooltipEssentialHTML += tooltipHTML
        } else {
            tooltipNonEssentialHTML += tooltipHTML
        }

        if(service.status === 'yellow' && status !== 'red'){
            status = 'yellow'
        } else if(service.status === 'red'){
            status = 'red'
        } else {
            if(service.status === 'grey'){
                ++greyCount
            }
            ++greenCount
        }

    }

    if(greenCount === statuses.length){
        if(greyCount === statuses.length){
            status = 'grey'
        } else {
            status = 'green'
        }
    }
    
    const statusEssential = document.getElementById('mojangStatusEssentialContainer')
    const statusNonEssential = document.getElementById('mojangStatusNonEssentialContainer')
    const statusIcon = document.getElementById('mojang_status_icon')
    if (statusEssential) statusEssential.innerHTML = tooltipEssentialHTML
    if (statusNonEssential) statusNonEssential.innerHTML = tooltipNonEssentialHTML
    if (statusIcon) statusIcon.style.color = MojangRestAPI.statusToHex(status)
}

const refreshServerStatus = async (fade = false) => {
    loggerLanding.info('Refreshing Server Status')
    const distro = await DistroAPI.getDistribution()
    const serv = distro ? distro.getServerById(ConfigManager.getSelectedServer()) : null

    let pLabel = Lang.queryJS('landing.serverStatus.server')
    let pVal = Lang.queryJS('landing.serverStatus.offline')

    if(serv && (serv.hostname || serv.rawServer?.address)){
        try {
            const host = serv.hostname || (serv.rawServer.address ? serv.rawServer.address.split(':')[0] : 'localhost')
            const port = serv.port || (serv.rawServer.address && serv.rawServer.address.includes(':') ? Number.parseInt(serv.rawServer.address.split(':')[1]) : 25565)
            const proto = serv.rawServer?.minecraftVersion === '1.21.1' ? 767 : 47
            const servStat = await getServerStatus(proto, host, port)
            if(servStat?.players){
                pLabel = Lang.queryJS('landing.serverStatus.players')
                pVal = servStat.players.online + '/' + servStat.players.max
            }
        } catch (err) {
            loggerLanding.warn('Unable to refresh server status, assuming offline.')
            loggerLanding.debug(err)
        }
    }
    const playerLabel = document.getElementById('landingPlayerLabel')
    const playerCount = document.getElementById('player_count')
    const serverStatusWrapper = document.getElementById('server_status_wrapper')
    const updateStatus = () => {
        if (playerLabel) playerLabel.innerHTML = pLabel
        if (playerCount) playerCount.innerHTML = pVal
    }
    if(fade && serverStatusWrapper){
        $(serverStatusWrapper).fadeOut(250, () => {
            updateStatus()
            $(serverStatusWrapper).fadeIn(500)
        })
    } else {
        updateStatus()
    }
    
}

refreshMojangStatuses()
// Server Status is refreshed in uibinder.js on distributionIndexDone.

// Refresh statuses every hour. The status page itself refreshes every day so...
let mojangStatusListener = setInterval(() => refreshMojangStatuses(true), 60*60*1000)
// Set refresh rate to once every 5 minutes.
let serverStatusListener = setInterval(() => refreshServerStatus(true), 300000)

let recentLaunchLogs = []

/**
 * Shows an error overlay, toggles off the launch area, and provides a Discord reporting button.
 * 
 * @param {string} title The overlay title.
 * @param {string} desc The overlay description.
 * @param {Error|string} [err] Optional error instance or details for Discord reporting.
 */
function showLaunchFailure(title, desc, err = null){
    const errorDetails = err || desc
    try {
        LauncherLogger.error(`Launch Failure: ${title} - ${desc}`, err)
    } catch (_e) {}
    setOverlayContent(
        title,
        desc,
        'Понятно',
        '🚨 Отправить отчёт разработчику в Discord'
    )
    const dismissBtn = document.getElementById('overlayDismiss')
    if (dismissBtn) {
        dismissBtn.classList.add('overlayDiscordReportBtn')
        dismissBtn.style.display = 'inline-flex'
        dismissBtn.disabled = false
    }
    setOverlayHandler(() => {
        if (dismissBtn) dismissBtn.classList.remove('overlayDiscordReportBtn')
        toggleOverlay(false)
    })
    setDismissHandler(async () => {
        if (dismissBtn) {
            dismissBtn.disabled = true
            dismissBtn.innerText = 'Отправка отчёта в Discord... ⏳'
        }
        try {
            const DiscordReporter = require('./assets/js/discord_reporter')
            let combinedLogs = []
            if (recentLaunchLogs && recentLaunchLogs.length > 0) {
                combinedLogs.push('=== RECENT LAUNCH LOGS ===')
                combinedLogs.push(...recentLaunchLogs.slice(-250))
            }
            try {
                const serv = (DistroAPI.distro) ? DistroAPI.distro.getServerById(ConfigManager.getSelectedServer()) : null
                const servId = (serv && serv.rawServer) ? serv.rawServer.id : 'Lowuncher-School'
                const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), servId)
                const latestLogPath = pathLib.join(instanceDir, 'logs', 'latest.log')
                if (fsExtra.existsSync(latestLogPath)) {
                    const content = fsExtra.readFileSync(latestLogPath, 'utf8')
                    const lines = content.split('\n')
                    combinedLogs.push('\n=== INSTANCE LATEST.LOG (LAST 300 LINES) ===')
                    combinedLogs.push(...lines.slice(-300))
                }
            } catch (_fErr) {}

            const res = await DiscordReporter.sendReport({
                error: errorDetails,
                logs: combinedLogs.length > 0 ? combinedLogs : recentLaunchLogs,
                title: title
            })
            if (res.success) {
                if (dismissBtn) dismissBtn.innerText = '✓ Отчёт успешно отправлен!'
                const descEl = document.getElementById('overlayDesc')
                const linkNotice = res.mclogsUrl ? `<br><span style="display:inline-block; margin-top:6px; font-size:12px; color:#cbd5e1;">📄 Лог на mclo.gs: <a href="${res.mclogsUrl}" target="_blank" style="color: #60a5fa; text-decoration: underline;">${res.mclogsUrl}</a></span>` : ''
                if (descEl) descEl.innerHTML = desc + `<div style="margin-top: 14px; padding: 10px 14px; background: rgba(34, 197, 94, 0.15); border: 1px solid rgba(34, 197, 94, 0.35); border-radius: 8px; color: #4ade80; text-align: left;"><b style="color: #4ade80;">✓ Отчёт успешно отправлен разработчику в Discord!</b>${linkNotice}</div>`
            } else {
                if (dismissBtn) {
                    dismissBtn.disabled = false
                    dismissBtn.innerText = 'Повторить отправку'
                }
            }
        } catch (_sendErr) {
            if (dismissBtn) {
                dismissBtn.disabled = false
                dismissBtn.innerText = 'Ошибка отправки (повторить)'
            }
        }
    })
    toggleOverlay(true, true)
    toggleLaunchArea(false)
    setLaunchEnabled(true)
    isLaunchInProgress = false
}

/* System (Java) Scan */

/**
 * Asynchronously scan the system for valid Java installations.
 * 
 * @param {boolean} launchAfter Whether we should begin to launch after scanning. 
 */
async function asyncSystemScan(effectiveJavaOptions, launchAfter = true){

    setLaunchDetails(Lang.queryJS('landing.systemScan.checking'))
    toggleLaunchArea(true)
    setLaunchPercentage(0, 100)

    const jvmDetails = await discoverBestJvmInstallation(
        ConfigManager.getDataDirectory(),
        effectiveJavaOptions.supported
    )

    if(jvmDetails == null) {
        // If the result is null, no valid Java installation was found.
        // Show this information to the user.
        setOverlayContent(
            Lang.queryJS('landing.systemScan.noCompatibleJava'),
            Lang.queryJS('landing.systemScan.installJavaMessage', { 'major': effectiveJavaOptions.suggestedMajor }),
            Lang.queryJS('landing.systemScan.installJava'),
            Lang.queryJS('landing.systemScan.installJavaManually')
        )
        setOverlayHandler(() => {
            setLaunchDetails(Lang.queryJS('landing.systemScan.javaDownloadPrepare'))
            toggleOverlay(false)
            
            try {
                downloadJava(effectiveJavaOptions, launchAfter)
            } catch(err) {
                loggerLanding.error('Unhandled error in Java Download', err)
                showLaunchFailure(Lang.queryJS('landing.systemScan.javaDownloadFailureTitle'), Lang.queryJS('landing.systemScan.javaDownloadFailureText'))
            }
        })
        setDismissHandler(() => {
            $('#overlayContent').fadeOut(250, () => {
                //$('#overlayDismiss').toggle(false)
                setOverlayContent(
                    Lang.queryJS('landing.systemScan.javaRequired', { 'major': effectiveJavaOptions.suggestedMajor }),
                    Lang.queryJS('landing.systemScan.javaRequiredMessage', { 'major': effectiveJavaOptions.suggestedMajor }),
                    Lang.queryJS('landing.systemScan.javaRequiredDismiss'),
                    Lang.queryJS('landing.systemScan.javaRequiredCancel')
                )
                setOverlayHandler(() => {
                    toggleLaunchArea(false)
                    toggleOverlay(false)
                })
                setDismissHandler(() => {
                    toggleOverlay(false, true)

                    asyncSystemScan(effectiveJavaOptions, launchAfter)
                })
                $('#overlayContent').fadeIn(250)
            })
        })
        toggleOverlay(true, true)
    } else {
        // Java installation found, use this to launch the game.
        const javaExec = javaExecFromRoot(jvmDetails.path)
        ConfigManager.setJavaExecutable(ConfigManager.getSelectedServer(), javaExec)
        ConfigManager.save()

        // We need to make sure that the updated value is on the settings UI.
        // Just incase the settings UI is already open.
        settingsJavaExecVal.value = javaExec
        await populateJavaExecDetails(settingsJavaExecVal.value)

        // TODO Callback hell, refactor
        // TODO Move this out, separate concerns.
        if(launchAfter){
            await dlAsync()
        }
    }

}

async function downloadJava(effectiveJavaOptions, launchAfter = true) {

    // TODO Error handling.
    // asset can be null.
    const asset = await latestOpenJDK(
        effectiveJavaOptions.suggestedMajor,
        ConfigManager.getDataDirectory(),
        effectiveJavaOptions.distribution)

    if(asset == null) {
        throw new Error(Lang.queryJS('landing.downloadJava.findJdkFailure'))
    }

    let received = 0
    await downloadFile(asset.url, asset.path, ({ transferred }) => {
        received = transferred
        setDownloadPercentage(Math.trunc((transferred/asset.size)*100))
    })
    setDownloadPercentage(100)

    if(received != asset.size) {
        loggerLanding.warn(`Java Download: Expected ${asset.size} bytes but received ${received}`)
        if(!await validateLocalFile(asset.path, asset.algo, asset.hash)) {
            log.error(`Hashes do not match, ${asset.id} may be corrupted.`)
            // Don't know how this could happen, but report it.
            throw new Error(Lang.queryJS('landing.downloadJava.javaDownloadCorruptedError'))
        }
    }

    // Extract
    // Show installing progress bar.
    remote.getCurrentWindow().setProgressBar(2)

    // Wait for extration to complete.
    const eLStr = Lang.queryJS('landing.downloadJava.extractingJava')
    let dotStr = ''
    setLaunchDetails(eLStr)
    const extractListener = setInterval(() => {
        if(dotStr.length >= 3){
            dotStr = ''
        } else {
            dotStr += '.'
        }
        setLaunchDetails(eLStr + dotStr)
    }, 750)

    const newJavaExec = await extractJdk(asset.path)

    // Extraction complete, remove the loading from the OS progress bar.
    remote.getCurrentWindow().setProgressBar(-1)

    // Extraction completed successfully.
    ConfigManager.setJavaExecutable(ConfigManager.getSelectedServer(), newJavaExec)
    ConfigManager.save()

    clearInterval(extractListener)
    setLaunchDetails(Lang.queryJS('landing.downloadJava.javaInstalled'))

    // TODO Callback hell
    // Refactor the launch functions
    asyncSystemScan(effectiveJavaOptions, launchAfter)

}

// Keep reference to Minecraft Process
let proc
// Is DiscordRPC enabled
let hasRPC = false
// Joined server regex
// Change this if your server uses something different.
const GAME_JOINED_REGEX = /\[.+\]: Sound engine started/
const GAME_LAUNCH_REGEX = /^\[.+\]: (?:MinecraftForge .+ Initialized|NeoForge .+ Initialized|ModLauncher .+ starting: .+|Loading Minecraft .+ with Fabric Loader .+|Setting user: .+)$/
const MIN_LINGER = 5000

async function dlAsync(login = true) {

    // Login parameter is temporary for debug purposes. Allows testing the validation/downloads without
    // launching the game.

    const loggerLaunchSuite = LoggerUtil.getLogger('LaunchSuite')

    setLaunchDetails(Lang.queryJS('landing.dlAsync.loadingServerInfo'))

    let distro

    try {
        distro = await DistroAPI.refreshDistributionOrFallback()
        onDistroRefresh(distro)
    } catch(err) {
        loggerLaunchSuite.error('Unable to refresh distribution index.', err)
        showLaunchFailure(Lang.queryJS('landing.dlAsync.fatalError'), Lang.queryJS('landing.dlAsync.unableToLoadDistributionIndex'))
        return
    }

    const serv = distro.getServerById(ConfigManager.getSelectedServer())

    if(login) {
        if(ConfigManager.getSelectedAccount() == null){
            loggerLanding.error('You must be logged into an account.')
            showLaunchFailure('Требуется авторизация', 'Пожалуйста, выберите или создайте профиль перед запуском игры.')
            setLaunchEnabled(true)
            return
        }
    }

    const isNeoForge = serv?.rawServer?.minecraftVersion?.startsWith('1.21') || serv?.rawServer?.loader === 'neoforge' || serv?.rawServer?.usePythonBridge

    if(isNeoForge) {
        loggerLaunchSuite.info('Launching via Lowuncher Python Bridge (minecraft-launcher-lib / NeoForge 1.21.1)...')
        setLaunchDetails('Подготовка запуска через Python Bridge...')
        toggleLaunchArea(true)
        setLaunchPercentage(5, 100)

        const LaunchStrategy = require('./assets/js/launch_strategy')
        const authUser = ConfigManager.getSelectedAccount()

        try {
            recentLaunchLogs = []
            if (typeof startPlaytimeTracker === 'function') startPlaytimeTracker()
            await LaunchStrategy.launch(serv, authUser, {
                onProgress: (percent, status) => {
                    setLaunchPercentage(percent, 100)
                    setLaunchDetails(status)
                },
                onStarted: (pid, version) => {
                    loggerLaunchSuite.info(`Game started with PID ${pid}. Switching launch button to STOP.`)
                    toggleLaunchArea(false)
                    setGameRunningState(true)
                },
                onLog: (line) => {
                    recentLaunchLogs.push(line)
                    if (recentLaunchLogs.length > 250) recentLaunchLogs.shift()
                    if(GAME_LAUNCH_REGEX.test(line)) {
                        toggleLaunchArea(false)
                        setGameRunningState(true)
                    }
                }
            })
            if (typeof stopPlaytimeTracker === 'function') stopPlaytimeTracker()
            setGameRunningState(false)
            toggleLaunchArea(false)
            setLaunchEnabled(true)
            isLaunchInProgress = false
            return
        } catch(err) {
            if (typeof stopPlaytimeTracker === 'function') stopPlaytimeTracker()
            setGameRunningState(false)
            toggleLaunchArea(false)
            loggerLaunchSuite.error('Launch error:', err)
            const rawMsg = err.message || 'Не удалось запустить процесс Minecraft.'
            const hintMsg = '\n\n💡 Совет: Попробуйте перезапустить лаунчер и нажать «Играть» заново. Если файлы не докачались, лаунчер продолжит загрузку автоматически.'
            showLaunchFailure('Ошибка запуска игры', rawMsg + hintMsg, err)
            setLaunchEnabled(true)
            isLaunchInProgress = false
            return
        }
    }

    setLaunchDetails(Lang.queryJS('landing.dlAsync.pleaseWait'))
    toggleLaunchArea(true)
    setLaunchPercentage(0, 100)

    const fullRepairModule = new FullRepair(
        ConfigManager.getCommonDirectory(),
        ConfigManager.getInstanceDirectory(),
        ConfigManager.getLauncherDirectory(),
        ConfigManager.getSelectedServer(),
        DistroAPI.isDevMode()
    )

    fullRepairModule.spawnReceiver()

    fullRepairModule.childProcess.on('error', (err) => {
        loggerLaunchSuite.error('Error during launch', err)
        showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringLaunchTitle'), err.message || Lang.queryJS('landing.dlAsync.errorDuringLaunchText'))
    })
    fullRepairModule.childProcess.on('close', (code, _signal) => {
        if(code !== 0){
            loggerLaunchSuite.error(`Full Repair Module exited with code ${code}, assuming error.`)
            showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringLaunchTitle'), Lang.queryJS('landing.dlAsync.seeConsoleForDetails'))
        }
    })

    loggerLaunchSuite.info('Validating files.')
    setLaunchDetails(Lang.queryJS('landing.dlAsync.validatingFileIntegrity'))
    let invalidFileCount = 0
    try {
        invalidFileCount = await fullRepairModule.verifyFiles(percent => {
            setLaunchPercentage(percent)
        })
        setLaunchPercentage(100)
    } catch (err) {
        loggerLaunchSuite.error('Error during file validation.')
        showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringFileVerificationTitle'), err.displayable || Lang.queryJS('landing.dlAsync.seeConsoleForDetails'))
        return
    }
    

    if(invalidFileCount > 0) {
        loggerLaunchSuite.info('Downloading files.')
        setLaunchDetails(Lang.queryJS('landing.dlAsync.downloadingFiles'))
        setLaunchPercentage(0)
        try {
            await fullRepairModule.download(percent => {
                setDownloadPercentage(percent)
            })
            setDownloadPercentage(100)
        } catch(err) {
            loggerLaunchSuite.error('Error during file download.')
            showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringFileDownloadTitle'), err.displayable || Lang.queryJS('landing.dlAsync.seeConsoleForDetails'))
            return
        }
    } else {
        loggerLaunchSuite.info('No invalid files, skipping download.')
    }

    // Remove download bar.
    remote.getCurrentWindow().setProgressBar(-1)

    fullRepairModule.destroyReceiver()

    setLaunchDetails(Lang.queryJS('landing.dlAsync.preparingToLaunch'))

    const mojangIndexProcessor = new MojangIndexProcessor(
        ConfigManager.getCommonDirectory(),
        serv.rawServer.minecraftVersion)
    const distributionIndexProcessor = new DistributionIndexProcessor(
        ConfigManager.getCommonDirectory(),
        distro,
        serv.rawServer.id
    )

    const modLoaderData = await distributionIndexProcessor.loadModLoaderVersionJson(serv)
    const versionData = await mojangIndexProcessor.getVersionJson()

    if(login) {
        const authUser = ConfigManager.getSelectedAccount()
        loggerLaunchSuite.info(`Sending selected account (${authUser.displayName}) to ProcessBuilder.`)
        let pb = new ProcessBuilder(serv, versionData, modLoaderData, authUser, remote.app.getVersion())
        setLaunchDetails(Lang.queryJS('landing.dlAsync.launchingGame'))

        // const SERVER_JOINED_REGEX = /\[.+\]: \[CHAT\] [a-zA-Z0-9_]{1,16} joined the game/
        const SERVER_JOINED_REGEX = new RegExp(`\\[.+\\]: \\[CHAT\\] ${authUser.displayName} joined the game`)

        const onLoadComplete = () => {
            toggleLaunchArea(false)
            if(hasRPC){
                DiscordWrapper.updateDetails(Lang.queryJS('landing.discord.loading'))
                proc.stdout.on('data', gameStateChange)
            }
            proc.stdout.removeListener('data', tempListener)
            proc.stderr.removeListener('data', gameErrorListener)
        }
        const start = Date.now()

        // Attach a temporary listener to the client output.
        // Will wait for a certain bit of text meaning that
        // the client application has started, and we can hide
        // the progress bar stuff.
        const tempListener = function(data){
            if(GAME_LAUNCH_REGEX.test(data.trim())){
                const diff = Date.now()-start
                if(diff < MIN_LINGER) {
                    setTimeout(onLoadComplete, MIN_LINGER-diff)
                } else {
                    onLoadComplete()
                }
            }
        }

        // Listener for Discord RPC.
        const gameStateChange = function(data){
            data = data.trim()
            if(SERVER_JOINED_REGEX.test(data)){
                DiscordWrapper.updateDetails(Lang.queryJS('landing.discord.joined'))
            } else if(GAME_JOINED_REGEX.test(data)){
                DiscordWrapper.updateDetails(Lang.queryJS('landing.discord.joining'))
            }
        }

        const gameErrorListener = function(data){
            data = data.trim()
            if(data.indexOf('Could not find or load main class net.minecraft.launchwrapper.Launch') > -1){
                loggerLaunchSuite.error('Game launch failed, LaunchWrapper was not downloaded properly.')
                showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringLaunchTitle'), Lang.queryJS('landing.dlAsync.launchWrapperNotDownloaded'))
            }
        }

        try {
            // Build Minecraft process.
            proc = pb.build()

            // Bind listeners to stdout.
            proc.stdout.on('data', tempListener)
            proc.stderr.on('data', gameErrorListener)

            setLaunchDetails(Lang.queryJS('landing.dlAsync.doneEnjoyServer'))

            // Init Discord Hook
            if(distro.rawDistribution.discord != null && serv.rawServer.discord != null){
                DiscordWrapper.initRPC(distro.rawDistribution.discord, serv.rawServer.discord)
                hasRPC = true
                proc.on('close', (code, signal) => {
                    loggerLaunchSuite.info('Shutting down Discord Rich Presence..')
                    DiscordWrapper.shutdownRPC()
                    hasRPC = false
                    proc = null
                })
            }

        } catch(err) {

            loggerLaunchSuite.error('Error during launch', err)
            showLaunchFailure(Lang.queryJS('landing.dlAsync.errorDuringLaunchTitle'), Lang.queryJS('landing.dlAsync.checkConsoleForDetails'))

        }
    }

}

/**
 * News Loading Functions
 */

// DOM Cache
const newsContent                   = document.getElementById('newsContent')
const newsArticleTitle              = document.getElementById('newsArticleTitle')
const newsArticleDate               = document.getElementById('newsArticleDate')
const newsArticleAuthor             = document.getElementById('newsArticleAuthor')
const newsArticleComments           = document.getElementById('newsArticleComments')
const newsNavigationStatus          = document.getElementById('newsNavigationStatus')
const newsArticleContentScrollable  = document.getElementById('newsArticleContentScrollable')
const nELoadSpan                    = document.getElementById('nELoadSpan')

// News slide caches.
let newsActive = false
let newsGlideCount = 0

/**
 * Show the news UI via a slide animation.
 * 
 * @param {boolean} up True to slide up, otherwise false. 
 */
function slide_(up){
    const lCUpper = document.querySelector('#landingContainer > #upper')
    const lCLLeft = document.querySelector('#landingContainer > #lower > #left')
    const lCLCenter = document.querySelector('#landingContainer > #lower > #center')
    const lCLRight = document.querySelector('#landingContainer > #lower > #right')
    const newsBtn = document.querySelector('#landingContainer > #lower > #center #content')
    const landingContainer = document.getElementById('landingContainer')
    const newsContainer = document.querySelector('#landingContainer > #newsContainer')

    newsGlideCount++

    if(up){
        lCUpper.style.top = '-200vh'
        lCLLeft.style.top = '-200vh'
        lCLCenter.style.top = '-200vh'
        lCLRight.style.top = '-200vh'
        newsBtn.style.top = '130vh'
        newsContainer.style.top = '0px'
        //date.toLocaleDateString('en-US', {month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: 'numeric'})
        //landingContainer.style.background = 'rgba(29, 29, 29, 0.55)'
        landingContainer.style.background = 'rgba(0, 0, 0, 0.50)'
        setTimeout(() => {
            if(newsGlideCount === 1){
                lCLCenter.style.transition = 'none'
                newsBtn.style.transition = 'none'
            }
            newsGlideCount--
        }, 2000)
    } else {
        setTimeout(() => {
            newsGlideCount--
        }, 2000)
        landingContainer.style.background = null
        lCLCenter.style.transition = null
        newsBtn.style.transition = null
        newsContainer.style.top = '100%'
        lCUpper.style.top = '0px'
        lCLLeft.style.top = '0px'
        lCLCenter.style.top = '0px'
        lCLRight.style.top = '0px'
        newsBtn.style.top = '10px'
    }
}

// Bind news button.
const newsButtonEl = document.getElementById('newsButton')
if (newsButtonEl) {
    newsButtonEl.onclick = () => {
        // Toggle tabbing.
        if(newsActive){
            $('#landingContainer *').removeAttr('tabindex')
            $('#newsContainer *').attr('tabindex', '-1')
        } else {
            $('#landingContainer *').attr('tabindex', '-1')
            $('#newsContainer, #newsContainer *, #lower, #lower #center *').removeAttr('tabindex')
            if(newsAlertShown){
                $('#newsButtonAlert').fadeOut(2000)
                newsAlertShown = false
                ConfigManager.setNewsCacheDismissed(true)
                ConfigManager.save()
            }
        }
        slide_(!newsActive)
        newsActive = !newsActive
    }
}

// Array to store article meta.
let newsArr = null

// News load animation listener.
let newsLoadingListener = null

/**
 * Set the news loading animation.
 * 
 * @param {boolean} val True to set loading animation, otherwise false.
 */
function setNewsLoading(val){
    const nELoadSpan = document.getElementById('nELoadSpan')
    if(!nELoadSpan) return
    if(val){
        const nLStr = Lang.queryJS('landing.news.checking')
        let dotStr = '..'
        nELoadSpan.innerHTML = nLStr + dotStr
        newsLoadingListener = setInterval(() => {
            if(dotStr.length >= 3){
                dotStr = ''
            } else {
                dotStr += '.'
            }
            if(nELoadSpan) nELoadSpan.innerHTML = nLStr + dotStr
        }, 750)
    } else {
        if(newsLoadingListener != null){
            clearInterval(newsLoadingListener)
            newsLoadingListener = null
        }
    }
}

// Bind retry button.
if (typeof newsErrorRetry !== 'undefined' && newsErrorRetry) {
    newsErrorRetry.onclick = () => {
        $('#newsErrorFailed').fadeOut(250, () => {
            initNews()
            $('#newsErrorLoading').fadeIn(250)
        })
    }
}

if (typeof newsArticleContentScrollable !== 'undefined' && newsArticleContentScrollable) {
    newsArticleContentScrollable.onscroll = (e) => {
        if(e.target.scrollTop > Number.parseFloat($('.newsArticleSpacerTop').css('height'))){
            if (newsContent) newsContent.setAttribute('scrolled', '')
        } else {
            if (newsContent) newsContent.removeAttribute('scrolled')
        }
    }
}

/**
 * Reload the news without restarting.
 * 
 * @returns {Promise.<void>} A promise which resolves when the news
 * content has finished loading and transitioning.
 */
function reloadNews(){
    return new Promise((resolve, reject) => {
        $('#newsContent').fadeOut(250, () => {
            $('#newsErrorLoading').fadeIn(250)
            initNews().then(() => {
                resolve()
            })
        })
    })
}

let newsAlertShown = false

/**
 * Show the news alert indicating there is new news.
 */
function showNewsAlert(){
    newsAlertShown = true
    if (document.getElementById('newsButtonAlert')) {
        $(newsButtonAlert).fadeIn(250)
    }
}

async function digestMessage(str) {
    const msgUint8 = new TextEncoder().encode(str)
    const hashBuffer = await crypto.subtle.digest('SHA-1', msgUint8)
    const hashArray = Array.from(new Uint8Array(hashBuffer))
    const hashHex = hashArray
        .map((b) => b.toString(16).padStart(2, '0'))
        .join('')
    return hashHex
}

/**
 * Initialize News UI. This will load the news and prepare
 * the UI accordingly.
 * 
 * @returns {Promise.<void>} A promise which resolves when the news
 * content has finished loading and transitioning.
 */
async function initNews(){
    if (!document.getElementById('newsContainer')) {
        return
    }

    setNewsLoading(true)

    const news = await loadNews()

    newsArr = news?.articles || null

    if(newsArr == null){
        // News Loading Failed
        setNewsLoading(false)

        await $('#newsErrorLoading').fadeOut(250).promise()
        await $('#newsErrorFailed').fadeIn(250).promise()

    } else if(newsArr.length === 0) {
        // No News Articles
        setNewsLoading(false)

        ConfigManager.setNewsCache({
            date: null,
            content: null,
            dismissed: false
        })
        ConfigManager.save()

        await $('#newsErrorLoading').fadeOut(250).promise()
        await $('#newsErrorNone').fadeIn(250).promise()
    } else {
        // Success
        setNewsLoading(false)

        const lN = newsArr[0]
        const cached = ConfigManager.getNewsCache()
        let newHash = await digestMessage(lN.content)
        let newDate = new Date(lN.date)
        let isNew = false

        if(cached.date != null && cached.content != null){

            if(new Date(cached.date) >= newDate){

                // Compare Content
                if(cached.content !== newHash){
                    isNew = true
                    showNewsAlert()
                } else {
                    if(!cached.dismissed){
                        isNew = true
                        showNewsAlert()
                    }
                }

            } else {
                isNew = true
                showNewsAlert()
            }

        } else {
            isNew = true
            showNewsAlert()
        }

        if(isNew){
            ConfigManager.setNewsCache({
                date: newDate.getTime(),
                content: newHash,
                dismissed: false
            })
            ConfigManager.save()
        }

        const switchHandler = (forward) => {
            let cArt = parseInt(newsContent.getAttribute('article'))
            let nxtArt = forward ? (cArt >= newsArr.length-1 ? 0 : cArt + 1) : (cArt <= 0 ? newsArr.length-1 : cArt - 1)
    
            displayArticle(newsArr[nxtArt], nxtArt+1)
        }

        document.getElementById('newsNavigateRight').onclick = () => { switchHandler(true) }
        document.getElementById('newsNavigateLeft').onclick = () => { switchHandler(false) }
        await $('#newsErrorContainer').fadeOut(250).promise()
        displayArticle(newsArr[0], 1)
        await $('#newsContent').fadeIn(250).promise()
    }


}

/**
 * Add keyboard controls to the news UI. Left and right arrows toggle
 * between articles. If you are on the landing page, the up arrow will
 * open the news UI.
 */
document.addEventListener('keydown', (e) => {
    if(newsActive){
        if(e.key === 'ArrowRight' || e.key === 'ArrowLeft'){
            document.getElementById(e.key === 'ArrowRight' ? 'newsNavigateRight' : 'newsNavigateLeft').click()
        }
        // Interferes with scrolling an article using the down arrow.
        // Not sure of a straight forward solution at this point.
        // if(e.key === 'ArrowDown'){
        //     document.getElementById('newsButton').click()
        // }
    } else {
        if(getCurrentView() === VIEWS.landing){
            if(e.key === 'ArrowUp'){
                document.getElementById('newsButton').click()
            }
        }
    }
})

/**
 * Display a news article on the UI.
 * 
 * @param {Object} articleObject The article meta object.
 * @param {number} index The article index.
 */
function displayArticle(articleObject, index){
    newsArticleTitle.innerHTML = articleObject.title
    newsArticleTitle.href = articleObject.link
    newsArticleAuthor.innerHTML = 'by ' + articleObject.author
    newsArticleDate.innerHTML = articleObject.date
    newsArticleComments.innerHTML = articleObject.comments
    newsArticleComments.href = articleObject.commentsLink
    newsArticleContentScrollable.innerHTML = '<div id="newsArticleContentWrapper"><div class="newsArticleSpacerTop"></div>' + articleObject.content + '<div class="newsArticleSpacerBot"></div></div>'
    Array.from(newsArticleContentScrollable.getElementsByClassName('bbCodeSpoilerButton')).forEach(v => {
        v.onclick = () => {
            const text = v.parentElement.getElementsByClassName('bbCodeSpoilerText')[0]
            text.style.display = text.style.display === 'block' ? 'none' : 'block'
        }
    })
    newsNavigationStatus.innerHTML = Lang.query('ejs.landing.newsNavigationStatus', {currentPage: index, totalPages: newsArr.length})
    newsContent.setAttribute('article', index-1)
}

/**
 * Load news information from the RSS feed specified in the
 * distribution index.
 */
async function loadNews(){

    const distroData = await DistroAPI.getDistribution()
    if(!distroData.rawDistribution.rss) {
        loggerLanding.debug('No RSS feed provided.')
        return null
    }

    const promise = new Promise((resolve, reject) => {
        
        const newsFeed = distroData.rawDistribution.rss
        const newsHost = new URL(newsFeed).origin + '/'
        $.ajax({
            url: newsFeed,
            success: (data) => {
                const items = $(data).find('item')
                const articles = []

                for(let i=0; i<items.length; i++){
                // JQuery Element
                    const el = $(items[i])

                    // Resolve date.
                    const date = new Date(el.find('pubDate').text()).toLocaleDateString('en-US', {month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: 'numeric'})

                    // Resolve comments.
                    let comments = el.find('slash\\:comments').text() || '0'
                    comments = comments + ' Comment' + (comments === '1' ? '' : 's')

                    // Fix relative links in content.
                    let content = el.find('content\\:encoded').text()
                    let regex = /src="(?!http:\/\/|https:\/\/)(.+?)"/g
                    let matches
                    while((matches = regex.exec(content))){
                        content = content.replace(`"${matches[1]}"`, `"${newsHost + matches[1]}"`)
                    }

                    let link   = el.find('link').text()
                    let title  = el.find('title').text()
                    let author = el.find('dc\\:creator').text()

                    // Generate article.
                    articles.push(
                        {
                            link,
                            title,
                            date,
                            author,
                            content,
                            comments,
                            commentsLink: link + '#comments'
                        }
                    )
                }
                resolve({
                    articles
                })
            },
            timeout: 2500
        }).catch(err => {
            resolve({
                articles: null
            })
        })
    })

    return await promise
}

// ==========================================================================
// MODERN SHELL & TAB NAVIGATION (MOCKUP STYLE)
// ==========================================================================

function switchModernTab(tabId) {
    document.querySelectorAll('.sidebarNavBtn').forEach(b => {
        b.classList.toggle('active', b.getAttribute('data-tab') === tabId)
    })
    document.querySelectorAll('.modernTabPane').forEach(p => {
        p.classList.toggle('active', p.id === tabId)
    })

    if(tabId === 'tabAccounts') {
        renderModernAccountsList()
    } else if(tabId === 'tabSkin') {
        updateSkinTabUI()
    } else if(tabId === 'tabSettings') {
        loadModernSettings()
    }
}
window.switchModernTab = switchModernTab

// Bind Navigation Buttons
const navItemHome = document.getElementById('navItemHome')
if(navItemHome) navItemHome.onclick = () => switchModernTab('tabHome')

const navItemAccounts = document.getElementById('navItemAccounts')
if(navItemAccounts) navItemAccounts.onclick = () => switchModernTab('tabAccounts')

const navItemSkin = document.getElementById('navItemSkin')
if(navItemSkin) navItemSkin.onclick = () => switchModernTab('tabSkin')

const navItemSettings = document.getElementById('navItemSettings')
if(navItemSettings) navItemSettings.onclick = () => switchModernTab('tabSettings')

const navItemDiscord = document.getElementById('navItemDiscord')
if(navItemDiscord) {
    navItemDiscord.onclick = async () => {
        const discordUrl = 'https://discord.gg/uWbNGvVjg'
        try {
            await shell.openExternal(discordUrl)
        } catch (err) {
            console.warn('Failed to open Discord via shell:', err)
            clipboard.writeText(discordUrl)
            alert('Ссылка на наш Discord сервер скопирована в буфер обмена:\n' + discordUrl)
        }
    }
}

const sidebarProfileCard = document.getElementById('sidebarProfileCard')
if(sidebarProfileCard) {
    sidebarProfileCard.onclick = () => switchModernTab('tabAccounts')
}

// Open game directory button (...)
const heroBtnFolder = document.getElementById('heroBtnFolder')
if(heroBtnFolder) {
    heroBtnFolder.onclick = () => {
        const serv = (DistroAPI.distro) ? DistroAPI.distro.getServerById(ConfigManager.getSelectedServer()) : null
        const servId = (serv && serv.rawServer) ? serv.rawServer.id : 'Lowuncher-School'
        const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), servId)
        fsExtra.ensureDirSync(instanceDir)
        shell.openPath(instanceDir)
    }
}

/**
 * Triggers a manual diagnostic report collection and dispatch to Discord.
 * @param {HTMLElement} [btnEl] 
 */
async function triggerDiagnosticReport(btnEl = null) {
    if (btnEl) {
        btnEl.disabled = true
        btnEl.dataset.origText = btnEl.innerHTML
        btnEl.innerHTML = 'Сбор логов...'
    }

    try {
        const DiscordReporter = require('./assets/js/discord_reporter')
        let combinedLogs = []
        if (recentLaunchLogs && recentLaunchLogs.length > 0) {
            combinedLogs.push('=== RECENT LAUNCH LOGS ===')
            combinedLogs.push(...recentLaunchLogs.slice(-250))
        }

        try {
            const serv = (DistroAPI.distro) ? DistroAPI.distro.getServerById(ConfigManager.getSelectedServer()) : null
            const servId = (serv && serv.rawServer) ? serv.rawServer.id : 'Lowuncher-School'
            const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), servId)

            const latestLogPath = pathLib.join(instanceDir, 'logs', 'latest.log')
            if (fsExtra.existsSync(latestLogPath)) {
                const content = fsExtra.readFileSync(latestLogPath, 'utf8')
                const lines = content.split('\n')
                combinedLogs.push('\n=== INSTANCE LATEST.LOG (LAST 300 LINES) ===')
                combinedLogs.push(...lines.slice(-300))
            }

            const crashDir = pathLib.join(instanceDir, 'crash-reports')
            if (fsExtra.existsSync(crashDir)) {
                const files = fsExtra.readdirSync(crashDir).filter(f => f.endsWith('.txt'))
                if (files.length > 0) {
                    files.sort((a, b) => {
                        return fsExtra.statSync(pathLib.join(crashDir, b)).mtimeMs - fsExtra.statSync(pathLib.join(crashDir, a)).mtimeMs
                    })
                    const newestCrash = files[0]
                    const crashContent = fsExtra.readFileSync(pathLib.join(crashDir, newestCrash), 'utf8')
                    combinedLogs.push(`\n=== CRASH REPORT: ${newestCrash} ===`)
                    combinedLogs.push(crashContent.slice(0, 15000))
                }
            }
        } catch (_fileErr) {
            console.warn('Could not read instance files for diagnostics:', _fileErr)
        }

        if (btnEl) btnEl.innerHTML = 'Отправка в Discord...'

        const res = await DiscordReporter.sendReport({
            title: 'Пользовательский диагностический отчёт',
            logs: combinedLogs,
            error: 'Пользователь запросил отправку отчёта и логов разработчику'
        })

        if (btnEl) {
            btnEl.disabled = false
            btnEl.innerHTML = btnEl.dataset.origText || 'Отправить отчёт в Discord'
        }

        const dismissBtn = document.getElementById('overlayDismiss')
        if (dismissBtn) {
            dismissBtn.classList.remove('overlayDiscordReportBtn')
            dismissBtn.style.display = 'none'
        }

        if (res.success) {
            const linkNotice = res.mclogsUrl ? `<br><br><span style="display:inline-block; margin-top:8px; padding:6px 12px; background:rgba(59,130,246,0.15); border:1px solid rgba(59,130,246,0.3); border-radius:6px; font-size:12px;">📄 Ссылка на лог mclo.gs: <a href="${res.mclogsUrl}" target="_blank" style="color: #60a5fa; text-decoration: underline;">${res.mclogsUrl}</a></span>` : ''
            setOverlayContent(
                'Отчёт отправлен',
                `Диагностические данные и логи успешно отправлены разработчику в Discord!${linkNotice}`,
                'Закрыть'
            )
            setOverlayHandler(() => toggleOverlay(false))
            setDismissHandler(null)
            toggleOverlay(true, true)
        } else {
            setOverlayContent(
                'Ошибка отправки',
                `Не удалось отправить отчёт в Discord: ${res.error || 'Сетевой сбой'}. Проверьте подключение к интернету.`,
                'Закрыть'
            )
            setOverlayHandler(() => toggleOverlay(false))
            setDismissHandler(null)
            toggleOverlay(true, true)
        }
    } catch (err) {
        if (btnEl) {
            btnEl.disabled = false
            btnEl.innerHTML = btnEl.dataset.origText || 'Отправить отчёт в Discord'
        }
        setOverlayContent(
            'Ошибка',
            `Произошла ошибка при отправке отчёта: ${err.message}`,
            'Закрыть'
        )
        setOverlayHandler(() => toggleOverlay(false))
        setDismissHandler(null)
        toggleOverlay(true, true)
    }
}

// Dedicated Report buttons
const heroBtnReport = document.getElementById('heroBtnReport')
if (heroBtnReport) {
    heroBtnReport.onclick = () => triggerDiagnosticReport(heroBtnReport)
}

const modernBtnSendReport = document.getElementById('modernBtnSendReport')
if (modernBtnSendReport) {
    modernBtnSendReport.onclick = () => triggerDiagnosticReport(modernBtnSendReport)
}

// Hero version pill
const heroVersionPill = document.getElementById('heroVersionPill')
if(heroVersionPill) {
    heroVersionPill.style.cursor = 'pointer'
    heroVersionPill.onclick = () => {
        const configuredHost = ConfigManager.getCustomProperty('serverHost')
        const serv = (DistroAPI.distro) ? DistroAPI.distro.getServerById(ConfigManager.getSelectedServer()) : null
        const targetHost = configuredHost || (serv && serv.rawServer && serv.rawServer.address)
        const modernServerMeta = document.getElementById('modernServerMeta')
        if (targetHost) {
            clipboard.writeText(targetHost)
            if (modernServerMeta) {
                const original = modernServerMeta.innerText
                modernServerMeta.innerText = 'IP скопирован!'
                setTimeout(() => { modernServerMeta.innerText = original }, 2000)
            }
        } else {
            if (modernServerMeta) {
                const original = modernServerMeta.innerText
                modernServerMeta.innerText = 'Укажите IP в Настройках'
                setTimeout(() => { modernServerMeta.innerText = original }, 2500)
            }
        }
    }
}

// ==========================================================================
// PLAYTIME TRACKING (Persistent & Real-time)
// ==========================================================================
let totalPlaytimeSec = Number(ConfigManager.getCustomProperty('totalPlaytimeSeconds')) || 0
if (totalPlaytimeSec === 5220) {
    totalPlaytimeSec = 0
    ConfigManager.setCustomProperty('totalPlaytimeSeconds', 0)
    ConfigManager.save()
}

function formatPlaytime(seconds) {
    const hours = Math.floor(seconds / 3600)
    const minutes = Math.floor((seconds % 3600) / 60)
    if (hours > 0) {
        return `${hours} ч ${minutes} мин`
    }
    return `${minutes} мин`
}

function updatePlaytimeUI() {
    const statPlaytimeVal = document.getElementById('statPlaytimeVal')
    if (statPlaytimeVal) {
        statPlaytimeVal.innerText = formatPlaytime(totalPlaytimeSec)
    }
}

updatePlaytimeUI()

let playtimeInterval = null
function startPlaytimeTracker() {
    if (playtimeInterval) clearInterval(playtimeInterval)
    playtimeInterval = setInterval(() => {
        totalPlaytimeSec++
        updatePlaytimeUI()
        if (totalPlaytimeSec % 60 === 0) {
            ConfigManager.setCustomProperty('totalPlaytimeSeconds', totalPlaytimeSec)
            ConfigManager.save()
        }
    }, 1000)
}

function stopPlaytimeTracker() {
    if (playtimeInterval) {
        clearInterval(playtimeInterval)
        playtimeInterval = null
    }
    ConfigManager.setCustomProperty('totalPlaytimeSeconds', totalPlaytimeSec)
    ConfigManager.save()
    updatePlaytimeUI()
}

const statPlaytimeCard = document.getElementById('statPlaytimeCard')
if(statPlaytimeCard) {
    statPlaytimeCard.onclick = () => {
        const acc = ConfigManager.getSelectedAccount()
        const user = acc ? (acc.displayName || acc.username) : 'Игрок'
        const hours = (totalPlaytimeSec / 3600).toFixed(1)
        alert(`📊 Статистика времени:\n• Наиграно: ${formatPlaytime(totalPlaytimeSec)} (~${hours} ч)\n• Сборка: 1.21.1 Supreme (NeoForge)\n• Аккаунт: ${user}`)
    }
}

// ==========================================================================
// SERVER STATUS CHECK (Non-blocking TCP with 2.5s timeout)
// ==========================================================================
const nodeNet = require('net')

const PRIMARY_SERVER = '89.248.236.145:27160'
const BACKUP_SERVER = 'ozdangalla.netmc.ru:25565'

async function checkLiveServerStatus() {
    const statStatusVal = document.getElementById('statStatusVal')
    const statStatusDot = document.getElementById('statStatusDot')
    if (statStatusDot) statStatusDot.className = 'statusDot statusDotChecking'
    if (statStatusVal) statStatusVal.innerText = 'Проверка...'

    function pingEndpoint(endpoint) {
        let host = endpoint.trim()
        let port = 25565
        if (host.includes(':')) {
            const parts = host.split(':')
            host = parts[0]
            port = Number.parseInt(parts[1], 10) || 25565
        }

        return new Promise(resolve => {
            const socket = new nodeNet.Socket()
            let done = false
            const timer = setTimeout(() => {
                if (!done) {
                    done = true
                    socket.destroy()
                    resolve(false)
                }
            }, 2500)

            socket.connect(port, host, () => {
                if (!done) {
                    done = true
                    clearTimeout(timer)
                    socket.destroy()
                    resolve(true)
                }
            })

            socket.on('error', () => {
                if (!done) {
                    done = true
                    clearTimeout(timer)
                    socket.destroy()
                    resolve(false)
                }
            })
        })
    }

    let isOnline = await pingEndpoint(PRIMARY_SERVER)
    if (!isOnline) {
        isOnline = await pingEndpoint(BACKUP_SERVER)
    }

    if (isOnline) {
        if (statStatusDot) statStatusDot.className = 'statusDot statusDotOnline'
        if (statStatusVal) statStatusVal.innerText = 'Онлайн'
    } else {
        if (statStatusDot) statStatusDot.className = 'statusDot statusDotOffline'
        if (statStatusVal) statStatusVal.innerText = 'Выключен'
    }
}

// Initial status ping and card click handler
checkLiveServerStatus()

const statStatusCard = document.getElementById('statStatusCard')
if(statStatusCard) {
    statStatusCard.onclick = () => {
        try {
            clipboard.writeText('89.248.236.145:27160')
        } catch (e) {
            console.warn('Clipboard write failed:', e)
        }
        const statCopyHintText = document.getElementById('statCopyHintText')
        const statCopyPrompt = document.getElementById('statCopyPrompt')
        if (statCopyPrompt && statCopyHintText) {
            statCopyPrompt.classList.add('statCopySuccess')
            statCopyHintText.innerText = '✓ Скопировано в буфер!'
            setTimeout(() => {
                statCopyPrompt.classList.remove('statCopySuccess')
                statCopyHintText.innerText = 'Нажмите для копирования IP'
            }, 2500)
        }
    }
}

// ==========================================================================
// MODS CARD ACTION
// ==========================================================================
const statModsCard = document.getElementById('statModsCard')
if(statModsCard) {
    statModsCard.onclick = () => {
        switchModernTab('tabSettings')
        const modsSubBtn = document.querySelector('.settingsSubTabBtn[data-subtab="subtabMods"]')
        if(modsSubBtn) modsSubBtn.click()
    }
}

// ==========================================================================
// ACCOUNTS TAB LOGIC
// ==========================================================================

function renderModernAccountsList() {
    const listContainer = document.getElementById('savedAccountsList')
    if (!listContainer) return

    const accounts = ConfigManager.getAuthAccounts() || {}
    const rawKeys = Object.keys(accounts)
    const selected = ConfigManager.getSelectedAccount()

    // Deduplicate accounts by displayName case-insensitively
    // If there is both a community and a microsoft account with the same name, prefer community unless microsoft is currently selected
    const seenMap = new Map()
    for (const uuid of rawKeys) {
        const acc = accounts[uuid]
        if (!acc || !acc.displayName) continue
        const nameLower = acc.displayName.trim().toLowerCase()
        const isCurrent = selected && selected.uuid === uuid

        if (!seenMap.has(nameLower)) {
            seenMap.set(nameLower, uuid)
        } else if (isCurrent) {
            seenMap.set(nameLower, uuid)
        } else {
            const existingUuid = seenMap.get(nameLower)
            const existingAcc = accounts[existingUuid]
            if (existingAcc && existingAcc.type === 'microsoft' && acc.type === 'community') {
                seenMap.set(nameLower, uuid)
            }
        }
    }

    const keys = Array.from(seenMap.values())

    if (keys.length === 0) {
        listContainer.innerHTML = '<div style="color: #71717a; font-size: 13px; padding: 12px 4px;">Нет сохраненных аккаунтов</div>'
        return
    }

    listContainer.innerHTML = ''
    keys.forEach(uuid => {
        const acc = accounts[uuid]
        const isCurrent = selected && selected.uuid === uuid
        const skinDonor = ConfigManager.getAccountSkinDonor(uuid)
        const skinKey = skinDonor || (((acc.type === 'offline' || acc.type === 'community') && acc.displayName) ? acc.displayName : acc.uuid)

        let typeLabel = 'Офлайн профиль'
        if (acc.type === 'community') typeLabel = 'Логин и пароль'
        else if (acc.type === 'microsoft') typeLabel = 'Microsoft лицензия'

        const item = document.createElement('div')
        item.className = 'savedAccountItem'
        item.innerHTML = `
            <div class="savedAccountItemLeft">
                <img class="savedAccountAvatar" src="https://mc-heads.net/avatar/${encodeURIComponent(skinKey)}/64" alt="${acc.displayName}" />
                <div>
                    <span class="savedAccountName">${acc.displayName}</span>
                    <span style="display:block; font-size:11px; color:#71717a;">${typeLabel}</span>
                </div>
            </div>
            <div class="savedAccountActions">
                ${isCurrent 
                    ? '<span class="accountActiveBadge">ВЫБРАН</span>' 
                    : `<button class="modernSecondaryBtn btnSelectAcc" data-uuid="${acc.uuid}">Выбрать</button>
                       <button class="modernSecondaryBtn btnDeleteAcc" data-uuid="${acc.uuid}" style="color:#ef4444;">Удалить</button>`
                }
            </div>
        `
        listContainer.appendChild(item)
    })

    listContainer.querySelectorAll('.btnSelectAcc').forEach(btn => {
        btn.onclick = () => {
            const uuid = btn.getAttribute('data-uuid')
            ConfigManager.setSelectedAccount(uuid)
            ConfigManager.save()
            updateSelectedAccount(ConfigManager.getSelectedAccount())
            renderModernAccountsList()
        }
    })

    listContainer.querySelectorAll('.btnDeleteAcc').forEach(btn => {
        btn.onclick = () => {
            const uuid = btn.getAttribute('data-uuid')
            ConfigManager.removeAuthAccount(uuid)
            ConfigManager.save()
            updateSelectedAccount(ConfigManager.getSelectedAccount())
            renderModernAccountsList()
        }
    })
}

// Add password-protected community account (Login acts as offline nickname)
const inputCommunityUser = document.getElementById('inputCommunityUser')
const inputCommunityPass = document.getElementById('inputCommunityPass')
const inputCommunityPin = document.getElementById('inputCommunityPin')
const authPinGroup = document.getElementById('authPinGroup')
const btnCommunityLogin = document.getElementById('btnCommunityLogin')
const communityAuthStatus = document.getElementById('communityAuthStatus')

const RESTRICTED_NICKS = ['9lame', 'lllllame']
const ADMIN_PIN = '6233'

if (inputCommunityUser) {
    inputCommunityUser.addEventListener('input', () => {
        const val = inputCommunityUser.value.trim().toLowerCase()
        const isProtected = RESTRICTED_NICKS.includes(val)
        if (authPinGroup) {
            authPinGroup.style.display = isProtected ? 'block' : 'none'
        } else if (inputCommunityPin) {
            inputCommunityPin.style.display = isProtected ? 'block' : 'none'
        }
    })
}

if (btnCommunityLogin && inputCommunityUser && inputCommunityPass) {
    btnCommunityLogin.onclick = async () => {
        const nick = inputCommunityUser.value.trim()
        const pass = inputCommunityPass.value.trim()
        if (!nick || !/^[a-zA-Z0-9_]{1,16}$/.test(nick)) {
            inputCommunityUser.focus()
            inputCommunityUser.style.borderColor = '#ef4444'
            if (communityAuthStatus) {
                communityAuthStatus.style.display = 'block'
                communityAuthStatus.style.color = '#ef4444'
                communityAuthStatus.innerText = 'Некорректный ник. Допустимо от 1 до 16 латинских символов и цифр.'
            }
            return
        }
        if (!pass || pass.length < 3) {
            inputCommunityPass.focus()
            inputCommunityPass.style.borderColor = '#ef4444'
            if (communityAuthStatus) {
                communityAuthStatus.style.display = 'block'
                communityAuthStatus.style.color = '#ef4444'
                communityAuthStatus.innerText = 'Пароль должен быть не менее 3 символов.'
            }
            return
        }

        // Restrict protected nicknames (9lame, LLLLLame) to owner with PIN 6233
        if (RESTRICTED_NICKS.includes(nick.toLowerCase())) {
            const enteredPin = inputCommunityPin ? inputCommunityPin.value.trim() : ''
            if (!enteredPin) {
                if (authPinGroup) authPinGroup.style.display = 'block'
                if (inputCommunityPin) {
                    inputCommunityPin.focus()
                    inputCommunityPin.style.borderColor = '#ef4444'
                }
                if (communityAuthStatus) {
                    communityAuthStatus.style.display = 'block'
                    communityAuthStatus.style.color = '#ef4444'
                    communityAuthStatus.innerText = 'Этот ник зарезервирован владельцем. Требуется секретный PIN-код.'
                }
                return
            }
            if (enteredPin !== ADMIN_PIN) {
                if (authPinGroup) authPinGroup.style.display = 'block'
                if (inputCommunityPin) {
                    inputCommunityPin.focus()
                    inputCommunityPin.style.borderColor = '#ef4444'
                }
                if (communityAuthStatus) {
                    communityAuthStatus.style.display = 'block'
                    communityAuthStatus.style.color = '#ef4444'
                    communityAuthStatus.innerText = 'Неверный PIN-код. Вход с этим никнеймом запрещён.'
                }
                return
            }
        }

        inputCommunityUser.style.borderColor = ''
        inputCommunityPass.style.borderColor = ''
        if (inputCommunityPin) inputCommunityPin.style.borderColor = ''

        try {
            const acc = await AuthManager.addCommunityAccount(nick, pass)
            ConfigManager.setSelectedAccount(acc.uuid)
            ConfigManager.save()
            updateSelectedAccount(acc)
            inputCommunityUser.value = ''
            inputCommunityPass.value = ''
            if (inputCommunityPin) {
                inputCommunityPin.value = ''
                inputCommunityPin.style.display = 'none'
            }
            renderModernAccountsList()
            if (communityAuthStatus) {
                communityAuthStatus.style.display = 'block'
                communityAuthStatus.style.color = '#10b981'
                communityAuthStatus.innerText = `✓ Профиль «${nick}» успешно активирован!`
                setTimeout(() => {
                    communityAuthStatus.style.display = 'none'
                    switchModernTab('tabHome')
                }, 1000)
            } else {
                switchModernTab('tabHome')
            }
        } catch (err) {
            inputCommunityPass.focus()
            inputCommunityPass.style.borderColor = '#ef4444'
            if (communityAuthStatus) {
                communityAuthStatus.style.display = 'block'
                communityAuthStatus.style.color = '#ef4444'
                communityAuthStatus.innerText = err.message || 'Ошибка входа'
            }
        }
    }
}

// Microsoft Login
const btnModernLoginMsft = document.getElementById('btnModernLoginMsft')
if (btnModernLoginMsft) {
    btnModernLoginMsft.onclick = () => {
        const { MSFT_OPCODE } = require('./assets/js/ipcconstants')
        ipcRenderer.send(MSFT_OPCODE.OPEN_LOGIN, VIEWS.landing, VIEWS.landing)
    }
}

// ==========================================================================
// SKIN TAB LOGIC
// ==========================================================================

function syncSkinRestorerOffline(uuid, donor) {
    if (!uuid) return
    try {
        const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), 'Lowuncher-School')
        const savedSkinsDir = pathLib.join(instanceDir, 'saved_skins')
        fsExtra.ensureDirSync(savedSkinsDir)
        const skinFile = pathLib.join(savedSkinsDir, `${uuid}.json`)
        if (donor) {
            const skinData = {
                provider: 'mojang',
                argument: donor,
                variant: 'CLASSIC'
            }
            fsExtra.writeFileSync(skinFile, JSON.stringify(skinData, null, 2), 'utf-8')
        } else {
            if (fsExtra.existsSync(skinFile)) {
                fsExtra.unlinkSync(skinFile)
            }
        }
    } catch (err) {
        console.warn('Could not write SkinRestorer offline skin file:', err)
    }
}

function updateSkinTabUI() {
    const selected = ConfigManager.getSelectedAccount()
    const donorInput = document.getElementById('skinDonorInput')
    const previewImg = document.getElementById('skinTabPreviewImage')
    const donorLabel = document.getElementById('skinTabCurrentDonorLabel')
    const cmdInput = document.getElementById('skinCommandInput')

    if (!selected) return

    const donor = ConfigManager.getAccountSkinDonor(selected.uuid)
    const effectiveKey = donor || selected.displayName || 'Steve'

    if (donorInput) donorInput.value = donor || ''
    if (previewImg) previewImg.src = `https://mc-heads.net/body/${encodeURIComponent(effectiveKey)}/right`
    if (donorLabel) donorLabel.innerText = donor ? `${donor} (кастомный)` : `${effectiveKey} (по умолчанию)`
    if (cmdInput) cmdInput.value = `/skin set ${effectiveKey}`
}

const skinApplyBtn = document.getElementById('skinApplyBtn')
const skinDonorInput = document.getElementById('skinDonorInput')
const skinApplyStatus = document.getElementById('skinApplyStatus')

function applySkinDonor() {
    const selected = ConfigManager.getSelectedAccount()
    if (!selected) {
        alert('Сначала выберите или создайте профиль игрока в разделе «Аккаунты».')
        return
    }
    if (!skinDonorInput) return
    const donor = skinDonorInput.value.trim()
    if (!donor) {
        // Reset to default
        ConfigManager.setAccountSkinDonor(selected.uuid, null)
        ConfigManager.save()
        syncSkinRestorerOffline(selected.uuid, null)
        updateSelectedAccount(selected)
        updateSkinTabUI()
        if (skinApplyStatus) {
            skinApplyStatus.style.display = 'block'
            skinApplyStatus.style.color = '#10b981'
            skinApplyStatus.innerText = '✓ Скин сброшен на стандартный'
            setTimeout(() => { skinApplyStatus.style.display = 'none' }, 3000)
        }
        return
    }
    if (!/^[a-zA-Z0-9_]{1,16}$/.test(donor)) {
        skinDonorInput.focus()
        skinDonorInput.style.borderColor = '#ef4444'
        if (skinApplyStatus) {
            skinApplyStatus.style.display = 'block'
            skinApplyStatus.style.color = '#ef4444'
            skinApplyStatus.innerText = 'Некорректный ник. Допустимо от 1 до 16 латинских символов и цифр.'
        }
        return
    }
    skinDonorInput.style.borderColor = ''
    ConfigManager.setAccountSkinDonor(selected.uuid, donor)
    ConfigManager.save()
    syncSkinRestorerOffline(selected.uuid, donor)
    updateSelectedAccount(selected)
    updateSkinTabUI()
    if (skinApplyStatus) {
        skinApplyStatus.style.display = 'block'
        skinApplyStatus.style.color = '#10b981'
        skinApplyStatus.innerText = `✓ Скин игрока «${donor}» автоматически синхронизирован!`
        setTimeout(() => { skinApplyStatus.style.display = 'none' }, 3000)
    }
}

if (skinApplyBtn) {
    skinApplyBtn.onclick = applySkinDonor
}

if (skinDonorInput) {
    skinDonorInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault()
            applySkinDonor()
        }
    })
    skinDonorInput.addEventListener('input', () => {
        skinDonorInput.style.borderColor = ''
        if (skinApplyStatus) skinApplyStatus.style.display = 'none'
    })
}

const skinResetBtn = document.getElementById('skinResetBtn')
if (skinResetBtn) {
    skinResetBtn.onclick = () => {
        const selected = ConfigManager.getSelectedAccount()
        if (!selected) return
        ConfigManager.setAccountSkinDonor(selected.uuid, null)
        ConfigManager.save()
        syncSkinRestorerOffline(selected.uuid, null)
        if (skinDonorInput) skinDonorInput.value = ''
        updateSelectedAccount(selected)
        updateSkinTabUI()
        if (skinApplyStatus) {
            skinApplyStatus.style.display = 'block'
            skinApplyStatus.style.color = '#10b981'
            skinApplyStatus.innerText = '✓ Скин сброшен на стандартный'
            setTimeout(() => { skinApplyStatus.style.display = 'none' }, 3000)
        }
    }
}

// Copy skin command to clipboard
const skinCopyCommandBtn = document.getElementById('skinCopyCommandBtn')
const skinCommandInput = document.getElementById('skinCommandInput')
const skinCopyFeedback = document.getElementById('skinCopyFeedback')
if (skinCopyCommandBtn && skinCommandInput) {
    skinCopyCommandBtn.onclick = () => {
        clipboard.writeText(skinCommandInput.value)
        if (skinCopyFeedback) {
            skinCopyFeedback.style.display = 'block'
            setTimeout(() => {
                skinCopyFeedback.style.display = 'none'
            }, 2500)
        }
    }
}

// ==========================================================================
// SETTINGS TAB LOGIC
// ==========================================================================

function loadModernSettings() {
    // Sub-tab navigation
    document.querySelectorAll('.settingsSubTabBtn').forEach(btn => {
        btn.onclick = () => {
            document.querySelectorAll('.settingsSubTabBtn').forEach(b => b.classList.remove('active'))
            document.querySelectorAll('.settingsSubContent').forEach(c => c.classList.remove('active'))
            btn.classList.add('active')
            const target = btn.getAttribute('data-subtab')
            const content = document.getElementById(target)
            if (content) content.classList.add('active')
        }
    })

    // System RAM calculation and Slider Setup
    const ramRange = document.getElementById('modernRamRange')
    const ramBadge = document.getElementById('modernRamValBadge')
    const ramRec = document.getElementById('modernRamRecommendation')
    const ticksContainer = document.getElementById('modernRamTicksContainer')

    const totalMemGB = Math.max(4, Math.round(nodeOs.totalmem() / 1073741824))
    const halfMemGB = Math.min(16, Math.max(4, Math.floor(totalMemGB / 2)))

    if (ramRec) {
        ramRec.innerText = `Рекомендуется: половина от всей оперативной памяти компьютера (${halfMemGB} ГБ при ваших ${totalMemGB} ГБ ОЗУ) для плавной работы NeoForge 1.21.1 с модами`
    }

    if (ramRange && ramBadge) {
        const minRAM = 2
        const maxRAM = Math.max(8, Math.min(32, totalMemGB))
        ramRange.min = String(minRAM)
        ramRange.max = String(maxRAM)
        ramRange.step = '1'

        const sId = ConfigManager.getSelectedServer()
        let currentVal = `${halfMemGB}G`
        try {
            currentVal = ConfigManager.getMaxRAM(sId) || `${halfMemGB}G`
        } catch {
            currentVal = `${halfMemGB}G`
        }

        let gb = halfMemGB
        if (typeof currentVal === 'string') {
            if (currentVal.endsWith('G')) {
                gb = parseFloat(currentVal)
            } else if (currentVal.endsWith('M')) {
                gb = Math.round(parseFloat(currentVal) / 1024)
            }
        } else if (typeof currentVal === 'number') {
            gb = currentVal > 100 ? Math.round(currentVal / 1024) : currentVal
        }
        gb = Math.max(minRAM, Math.min(maxRAM, gb || halfMemGB))
        ramRange.value = String(gb)
        ramBadge.innerText = `${gb} ГБ`

        // Render aligned ticks
        if (ticksContainer) {
            const standardTicks = [2, 4, 6, 8, 12, 16, 24, 32].filter(v => v >= minRAM && v <= maxRAM)
            ticksContainer.innerHTML = standardTicks.map(val => {
                const percent = ((val - minRAM) / (maxRAM - minRAM)) * 100
                const isActive = Number(val) === Number(gb)
                return `<span class="ramTickLabel ${isActive ? 'active' : ''}" data-val="${val}" style="left: ${percent}%;">${val} ГБ</span>`
            }).join('')

            ticksContainer.querySelectorAll('.ramTickLabel').forEach(label => {
                label.onclick = () => {
                    const v = Number(label.getAttribute('data-val'))
                    ramRange.value = String(v)
                    ramRange.dispatchEvent(new Event('input'))
                    ramRange.dispatchEvent(new Event('change'))
                }
            })
        }

        ramRange.oninput = () => {
            const val = Number(ramRange.value)
            ramBadge.innerText = `${val} ГБ`
            if (ticksContainer) {
                ticksContainer.querySelectorAll('.ramTickLabel').forEach(l => {
                    l.classList.toggle('active', Number(l.getAttribute('data-val')) === val)
                })
            }
        }
        ramRange.onchange = () => {
            try {
                ConfigManager.setMaxRAM(sId, `${ramRange.value}G`)
                ConfigManager.save()
            } catch (err) {
                console.warn('Failed to save max RAM:', err)
            }
        }
    }

    // Java reset button
    const btnResetJava = document.getElementById('modernBtnResetJava')
    if (btnResetJava) {
        btnResetJava.onclick = () => {
            const sId = ConfigManager.getSelectedServer()
            if (sId && ConfigManager.setJavaExecutable) {
                ConfigManager.setJavaExecutable(sId, null)
                ConfigManager.save()
            }
            alert('Исполняемый файл Java сброшен на встроенную версию (Java 21).')
        }
    }

    // Folders & Mods
    const serv = (DistroAPI.distro) ? DistroAPI.distro.getServerById(ConfigManager.getSelectedServer()) : null
    const servId = (serv && serv.rawServer) ? serv.rawServer.id : 'Lowuncher-School'
    const instanceDir = pathLib.join(ConfigManager.getInstanceDirectory(), servId)
    const modsPath = pathLib.join(instanceDir, 'mods')
    fsExtra.ensureDirSync(modsPath)

    const launcherDirInput = document.getElementById('modernLauncherDirInput')
    if (launcherDirInput) launcherDirInput.value = ConfigManager.getDataDirectory()

    const btnOpenLauncherDir = document.getElementById('modernBtnOpenLauncherDir')
    if (btnOpenLauncherDir) {
        btnOpenLauncherDir.onclick = () => shell.openPath(ConfigManager.getDataDirectory())
    }

    // Instance Directory button & input in Settings -> Mods
    const instancePathInput = document.getElementById('modernInstancePathInput')
    if (instancePathInput) instancePathInput.value = instanceDir

    const btnOpenInstanceDir = document.getElementById('modernBtnOpenInstanceDir')
    if (btnOpenInstanceDir) {
        btnOpenInstanceDir.onclick = () => shell.openPath(instanceDir)
    }

    // Function to scan and render local mods
    function refreshLocalModsUI() {
        const modsListContainer = document.getElementById('modernModsListContainer')
        const countTitle = document.getElementById('modernModsCountTitle')
        const statModsVal = document.getElementById('statModsVal')

        let modFiles = []
        try {
            if (fsExtra.existsSync(modsPath)) {
                modFiles = fsExtra.readdirSync(modsPath).filter(f => f.endsWith('.jar') && !f.endsWith('.disabled'))
            }
        } catch (e) {
            console.warn('Could not read mods folder:', e)
        }

        if (countTitle) countTitle.innerText = `Обнаруженные модификации (${modFiles.length} шт.)`
        if (statModsVal) statModsVal.innerText = `${modFiles.length} активных`

        if (modsListContainer) {
            if (modFiles.length > 0) {
                modsListContainer.innerHTML = modFiles.map(f => {
                    const stats = fsExtra.statSync(pathLib.join(modsPath, f))
                    const sizeMB = (stats.size / (1024 * 1024)).toFixed(1)
                    return `
                        <div class="modernModItem">
                            <span class="modernModName">${f}</span>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span style="font-size:11px; color:#a1a1aa;">${sizeMB} МБ</span>
                                <span class="modernModBadge">Активен</span>
                            </div>
                        </div>
                    `
                }).join('')
            } else {
                modsListContainer.innerHTML = `
                    <div style="color:#71717a; font-size:13px; padding:12px 0;">
                        В папке <code>${modsPath}</code> пока нет .jar файлов.
                    </div>
                `
            }
        }
    }

    refreshLocalModsUI()

    const btnRefreshMods = document.getElementById('modernBtnRefreshMods')
    if (btnRefreshMods) {
        btnRefreshMods.onclick = refreshLocalModsUI
    }

    // Auto-update controls in About tab & header
    const btnCheckUpdates = document.getElementById('modernBtnCheckUpdates')
    const btnInstallUpdate = document.getElementById('modernBtnInstallUpdate')
    const updateStatusBox = document.getElementById('modernUpdateStatusBox')
    const updateHeaderPill = document.getElementById('modernUpdateHeaderPill')
    const updateHeaderPillText = document.getElementById('modernUpdateHeaderPillText')

    if (btnCheckUpdates) {
        btnCheckUpdates.onclick = () => {
            if (updateStatusBox) updateStatusBox.innerText = 'Проверка наличия обновлений на GitHub...'
            ipcRenderer.send('autoUpdateAction', 'checkForUpdate')
        }
    }

    if (btnInstallUpdate) {
        btnInstallUpdate.onclick = () => {
            ipcRenderer.send('autoUpdateAction', 'installUpdateNow')
        }
    }

    if (updateHeaderPill) {
        updateHeaderPill.onclick = () => {
            if (btnInstallUpdate && btnInstallUpdate.style.display !== 'none') {
                ipcRenderer.send('autoUpdateAction', 'installUpdateNow')
            } else {
                const navSettings = document.getElementById('navItemSettings')
                if (navSettings) navSettings.click()
                const aboutSubtab = document.querySelector('.settingsSubTabBtn[data-subtab="subtabAbout"]')
                if (aboutSubtab) aboutSubtab.click()
            }
        }
    }

    const curAppVersion = remote && remote.app ? remote.app.getVersion() : '1.0.6'
    const appVerLabel = document.getElementById('modernAppVersionLabel')
    if (appVerLabel) appVerLabel.innerText = `Версия ${curAppVersion} (Release)`
    if (updateStatusBox) updateStatusBox.innerText = `Текущая версия актуальна (${curAppVersion}).`

    window.addEventListener('lowuncher-autoupdate', (e) => {
        const { action, info } = e.detail || {}
        if (action === 'checking-for-update') {
            if (updateStatusBox) updateStatusBox.innerText = 'Проверка наличия обновлений...'
        } else if (action === 'update-available') {
            const ver = info && info.version ? `v${info.version}` : ''
            if (updateStatusBox) updateStatusBox.innerText = `Найдено обновление ${ver}! Начинается загрузка...`
            if (updateHeaderPill) {
                updateHeaderPill.style.display = 'flex'
                if (updateHeaderPillText) updateHeaderPillText.innerText = `Загрузка обновления ${ver}...`
            }
        } else if (action === 'download-progress') {
            const pct = info && typeof info.percent === 'number' ? info.percent.toFixed(0) : '0'
            const speed = info && info.bytesPerSecond ? `${(info.bytesPerSecond / 1048576).toFixed(1)} МБ/с` : ''
            const text = `Загрузка обновления: ${pct}%${speed ? ' (' + speed + ')' : ''}`
            if (updateStatusBox) updateStatusBox.innerText = text
            if (updateHeaderPill) {
                updateHeaderPill.style.display = 'flex'
                if (updateHeaderPillText) updateHeaderPillText.innerText = text
            }
        } else if (action === 'update-downloaded') {
            const ver = info && info.version ? `v${info.version}` : ''
            if (updateStatusBox) updateStatusBox.innerText = `Обновление ${ver} готово к установке!`
            if (btnInstallUpdate) btnInstallUpdate.style.display = 'inline-block'
            if (updateHeaderPill) {
                updateHeaderPill.style.display = 'flex'
                if (updateHeaderPillText) updateHeaderPillText.innerText = `Обновление ${ver} готово (нажмите для установки)`
            }
        } else if (action === 'update-not-available') {
            const curVer = remote && remote.app ? remote.app.getVersion() : '1.0.6'
            if (updateStatusBox) updateStatusBox.innerText = `У вас установлена актуальная версия (${curVer}).`
            if (updateHeaderPill) updateHeaderPill.style.display = 'none'
        } else if (action === 'realerror') {
            if (updateStatusBox) updateStatusBox.innerText = 'Сервер обновлений временно недоступен. Актуальные релизы доступны на GitHub.'
        }
    })
}
