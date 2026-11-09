/**
 * Core UI functions are initialized in this file. This prevents
 * unexpected errors from breaking the core features. Specifically,
 * actions in this file should not require the usage of any internal
 * modules, excluding dependencies.
 */
// Requirements
var $                              = require('jquery')
var {ipcRenderer, shell, webFrame} = require('electron')
var remote                         = require('@electron/remote')
var isDev                          = require('./assets/js/isdev')
var { LoggerUtil }                 = require('helios-core')
var Lang                           = require('./assets/js/langloader')

const loggerUICore             = LoggerUtil.getLogger('UICore')
const loggerAutoUpdater        = LoggerUtil.getLogger('AutoUpdater')

// Log deprecation and process warnings.
process.traceProcessWarnings = true
process.traceDeprecation = true

// Disable eval function.
window.eval = global.eval = function () {
    throw new Error('Sorry, this app does not support window.eval().')
}

// Display warning when devtools window is opened.
remote.getCurrentWebContents().on('devtools-opened', () => {
    console.log('%cThe console is dark and full of terrors.', 'color: white; -webkit-text-stroke: 4px #a02d2a; font-size: 60px; font-weight: bold')
    console.log('%cIf you\'ve been told to paste something here, you\'re being scammed.', 'font-size: 16px')
    console.log('%cUnless you know exactly what you\'re doing, close this window.', 'font-size: 16px')
})

// Disable zoom, needed for darwin.
webFrame.setZoomLevel(0)
webFrame.setVisualZoomLevelLimits(1, 1)

// Initialize auto updates
let updateCheckListener
ipcRenderer.on('autoUpdateNotification', (event, arg, info) => {
    try {
        window.dispatchEvent(new CustomEvent('lowuncher-autoupdate', { detail: { action: arg, info } }))
    } catch (_e) {}
    switch(arg){
            case 'checking-for-update':
                loggerAutoUpdater.info('Checking for update..')
                settingsUpdateButtonStatus(Lang.queryJS('uicore.autoUpdate.checkingForUpdateButton'), true)
                break
            case 'update-available':
                loggerAutoUpdater.info('New update available', info.version)
                
                if(process.platform === 'darwin'){
                    info.darwindownload = `https://github.com/bublg95nelox-max/supreme/releases/download/v${info.version}/Lowuncher-${info.version}${process.arch === 'arm64' ? '-arm64' : '-x64'}.dmg`
                    showUpdateUI(info)
                }
                
                populateSettingsUpdateInformation(info)
                break
            case 'download-progress':
                if (info && typeof info.percent === 'number') {
                    settingsUpdateButtonStatus(`Загрузка: ${info.percent.toFixed(0)}%`, true)
                }
                break
            case 'update-downloaded':
                loggerAutoUpdater.info('Update ' + info.version + ' ready to be installed.')
                settingsUpdateButtonStatus(Lang.queryJS('uicore.autoUpdate.installNowButton'), false, () => {
                    if(!isDev){
                        ipcRenderer.send('autoUpdateAction', 'installUpdateNow')
                    }
                })
                showUpdateUI(info)
                break
            case 'update-not-available':
                loggerAutoUpdater.info('No new update found.')
                settingsUpdateButtonStatus(Lang.queryJS('uicore.autoUpdate.checkForUpdatesButton'))
                break
            case 'ready':
                updateCheckListener = setInterval(() => {
                    ipcRenderer.send('autoUpdateAction', 'checkForUpdate')
                }, 1800000)
                ipcRenderer.send('autoUpdateAction', 'checkForUpdate')
                break
            case 'realerror':
                if(info != null && info.code != null){
                    if(info.code === 'ERR_UPDATER_INVALID_RELEASE_FEED'){
                        loggerAutoUpdater.info('No suitable releases found.')
                    } else if(info.code === 'ERR_XML_MISSED_ELEMENT'){
                        loggerAutoUpdater.info('No releases found.')
                    } else {
                        loggerAutoUpdater.error('Error during update check..', info)
                        loggerAutoUpdater.debug('Error Code:', info.code)
                    }
                }
                break
            default:
                loggerAutoUpdater.info('Unknown argument', arg)
                break
        }
    })

/**
 * Send a notification to the main process changing the value of
 * allowPrerelease. If we are running a prerelease version, then
 * this will always be set to true, regardless of the current value
 * of val.
 * 
 * @param {boolean} val The new allow prerelease value.
 */
function changeAllowPrerelease(val){
    ipcRenderer.send('autoUpdateAction', 'allowPrereleaseChange', val)
}

function showUpdateUI(info){
    //TODO Make this message a bit more informative `${info.version}`
    const updateSeal = document.getElementById('image_seal_container')
    if (!updateSeal) return
    updateSeal.setAttribute('update', true)
    updateSeal.onclick = () => {
        /*setOverlayContent('Update Available', 'A new update for the launcher is available. Would you like to install now?', 'Install', 'Later')
        setOverlayHandler(() => {
            if(!isDev){
                ipcRenderer.send('autoUpdateAction', 'installUpdateNow')
            } else {
                console.error('Cannot install updates in development environment.')
                toggleOverlay(false)
            }
        })
        setDismissHandler(() => {
            toggleOverlay(false)
        })
        toggleOverlay(true, true)*/
        switchView(getCurrentView(), VIEWS.settings, 500, 500, () => {
            settingsNavItemListener(document.getElementById('settingsNavUpdate'), false)
        })
    }
}

/* jQuery Example
$(function(){
    loggerUICore.info('UICore Initialized');
})*/

function bindAllWindowControls() {
    const handleClose = (e) => {
        if (e) {
            e.preventDefault()
            e.stopPropagation()
        }
        console.log('[WindowControl] Close requested')
        try { ipcRenderer.send('window-close') } catch (_e) {}
        try {
            const curWin = remote.getCurrentWindow()
            if (curWin && !curWin.isDestroyed()) curWin.close()
        } catch (_e) {}
    }
    const handleMax = (e) => {
        if (e) {
            e.preventDefault()
            e.stopPropagation()
        }
        console.log('[WindowControl] Maximize/Restore requested')
        try { ipcRenderer.send('window-maximize') } catch (_e) {}
        if (document.activeElement) document.activeElement.blur()
    }
    const handleMin = (e) => {
        if (e) {
            e.preventDefault()
            e.stopPropagation()
        }
        console.log('[WindowControl] Minimize requested')
        try { ipcRenderer.send('window-minimize') } catch (_e) {}
        if (document.activeElement) document.activeElement.blur()
    }

    const bindSelector = (selector, handler) => {
        document.querySelectorAll(selector).forEach((el) => {
            el.onclick = handler
        })
    }

    bindSelector('#globalBtnClose, #modernBtnClose, #btnClose, .winControlClose, .fCb', handleClose)
    bindSelector('#globalBtnMax, #modernBtnMax, #btnMax, .winControlMax, .fRb', handleMax)
    bindSelector('#globalBtnMin, #modernBtnMin, #btnMin, .winControlMin, .fMb', handleMin)

    const dragArea = document.getElementById('modernWindowDragArea')
    if (dragArea) {
        dragArea.ondblclick = handleMax
    }
}
window.bindAllWindowControls = bindAllWindowControls

// Capture phase global delegation to guarantee clicks on traffic lights always trigger
document.addEventListener('click', (e) => {
    if (!e || !e.target) return
    const closeBtn = e.target.closest('#globalBtnClose, #modernBtnClose, #btnClose, .winControlClose')
    if (closeBtn) {
        e.preventDefault()
        e.stopPropagation()
        console.log('[WindowControl Delegation] Close clicked')
        try { ipcRenderer.send('window-close') } catch (_e) {}
        try {
            const curWin = remote.getCurrentWindow()
            if (curWin && !curWin.isDestroyed()) curWin.close()
        } catch (_e) {}
        return
    }
    const maxBtn = e.target.closest('#globalBtnMax, #modernBtnMax, #btnMax, .winControlMax')
    if (maxBtn) {
        e.preventDefault()
        e.stopPropagation()
        console.log('[WindowControl Delegation] Maximize clicked')
        try { ipcRenderer.send('window-maximize') } catch (_e) {}
        if (document.activeElement) document.activeElement.blur()
        return
    }
    const minBtn = e.target.closest('#globalBtnMin, #modernBtnMin, #btnMin, .winControlMin')
    if (minBtn) {
        e.preventDefault()
        e.stopPropagation()
        console.log('[WindowControl Delegation] Minimize clicked')
        try { ipcRenderer.send('window-minimize') } catch (_e) {}
        if (document.activeElement) document.activeElement.blur()
        return
    }
}, true)

function initUICore() {
    loggerUICore.info('UICore Initializing..')
    bindAllWindowControls()

    ipcRenderer.on('windowMaximizeState', (_event, isMax) => {
        document.body.classList.toggle('maximized', Boolean(isMax))
    })
    try {
        ipcRenderer.invoke('window-get-maximize-state').then((isMax) => {
            document.body.classList.toggle('maximized', Boolean(isMax))
        }).catch(() => {})
    } catch (_e) {}
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initUICore)
} else {
    initUICore()
}

document.addEventListener('readystatechange', function () {
    if (document.readyState === 'interactive' || document.readyState === 'complete'){
        bindAllWindowControls()

        // Remove focus from social media buttons once they're clicked.
        Array.from(document.getElementsByClassName('mediaURL')).map(val => {
            val.addEventListener('click', e => {
                document.activeElement.blur()
            })
        })

    } else if(document.readyState === 'complete'){

        //266.01
        //170.8
        //53.21
        // Bind progress bar length to length of bot wrapper
        //const targetWidth = document.getElementById("launch_content").getBoundingClientRect().width
        //const targetWidth2 = document.getElementById("server_selection").getBoundingClientRect().width
        //const targetWidth3 = document.getElementById("launch_button").getBoundingClientRect().width

        const launchDetails = document.getElementById('launch_details')
        const launchProgress = document.getElementById('launch_progress')
        const launchProgressLabel = document.getElementById('launch_progress_label')
        if (launchDetails) launchDetails.style.maxWidth = '100%'
        if (launchProgress) launchProgress.style.width = '100%'
        if (launchProgressLabel) launchProgressLabel.style.width = 'auto'
        
    }

}, false)

/**
 * Open web links in the user's default browser.
 */
$(document).on('click', 'a[href^="http"]', function(event) {
    event.preventDefault()
    shell.openExternal(this.href)
})

/**
 * Opens DevTools window if you hold (ctrl + shift + i).
 * This will crash the program if you are using multiple
 * DevTools, for example the chrome debugger in VS Code. 
 */
document.addEventListener('keydown', function (e) {
    if((e.key === 'I' || e.key === 'i') && e.ctrlKey && e.shiftKey){
        let window = remote.getCurrentWindow()
        window.toggleDevTools()
    }
})