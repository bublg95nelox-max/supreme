const child_process = require('child_process')
const path = require('path')
const fs = require('fs-extra')
const { LoggerUtil } = require('helios-core')

const logger = LoggerUtil.getLogger('PythonBridge')

class PythonBridge {

    static activeProc = null
    static gamePid = null

    /**
     * Checks if the Minecraft process or Python bridge is actively running.
     * @returns {boolean}
     */
    static isGameRunning() {
        return Boolean(PythonBridge.activeProc || PythonBridge.gamePid)
    }

    /**
     * Forcibly kills the Minecraft Java process and Python bridge subprocess.
     */
    static killGame() {
        logger.info(`Terminating Minecraft... Game PID: ${PythonBridge.gamePid}, Bridge PID: ${PythonBridge.activeProc ? PythonBridge.activeProc.pid : 'none'}`)
        if (PythonBridge.gamePid) {
            try {
                if (process.platform === 'win32') {
                    child_process.exec(`taskkill /F /T /PID ${PythonBridge.gamePid}`)
                } else {
                    process.kill(PythonBridge.gamePid, 'SIGKILL')
                }
            } catch (e) {
                logger.warn(`Failed to kill game process ${PythonBridge.gamePid}:`, e)
            }
            PythonBridge.gamePid = null
        }
        if (PythonBridge.activeProc) {
            try {
                if (process.platform === 'win32') {
                    child_process.exec(`taskkill /F /T /PID ${PythonBridge.activeProc.pid}`)
                } else {
                    PythonBridge.activeProc.kill('SIGKILL')
                }
            } catch (e) {
                logger.warn('Failed to kill bridge process:', e)
            }
            PythonBridge.activeProc = null
        }
    }

    /**
     * Resolves the root directory where `core` is located.
     */
    static resolveRootDir() {
        if (process.resourcesPath && fs.existsSync(path.join(process.resourcesPath, 'core'))) {
            return process.resourcesPath
        }
        return path.resolve(__dirname, '..', '..', '..')
    }

    /**
     * Resolves the best python executable path.
     */
    static resolvePythonPath() {
        if (process.env.LOWUNCHER_PYTHON) {
            return process.env.LOWUNCHER_PYTHON
        }

        const rootDir = PythonBridge.resolveRootDir()
        
        // 1. Check local virtual environment (.venv)
        const venvWin = path.join(rootDir, '.venv', 'Scripts', 'python.exe')
        const venvUnix = path.join(rootDir, '.venv', 'bin', 'python')
        if (process.platform === 'win32' && fs.existsSync(venvWin)) {
            return venvWin
        }
        if (fs.existsSync(venvUnix)) {
            return venvUnix
        }

        // 2. Check bundled portable Python in resources
        if (process.resourcesPath) {
            const bundledWin = path.join(process.resourcesPath, 'python', 'python.exe')
            const bundledUnix = path.join(process.resourcesPath, 'python', 'bin', 'python')
            if (process.platform === 'win32' && fs.existsSync(bundledWin)) return bundledWin
            if (fs.existsSync(bundledUnix)) return bundledUnix
        }

        // 3. Fallback to system python
        return process.platform === 'win32' ? 'python' : 'python3'
    }

    /**
     * Runs Minecraft launch and installation via core.bridge_cli
     * 
     * @param {Object} opts
     * @param {string} opts.gameDir
     * @param {string} opts.mcVersion
     * @param {string} opts.loader
     * @param {string} opts.loaderVersion
     * @param {string} opts.username
     * @param {string} opts.uuid
     * @param {string} opts.token
     * @param {number} opts.ram
     * @param {string} [opts.manifestUrl]
     * @param {string} [opts.serverHost]
     * @param {number} [opts.serverPort]
     * @param {Function} [opts.onProgress] callback(percent, status)
     * @param {Function} [opts.onLog] callback(line)
     * @returns {Promise.<number>} exitCode
     */
    static launchGame(opts) {
        return new Promise((resolve, reject) => {
            const pythonExe = PythonBridge.resolvePythonPath()
            const rootDir = PythonBridge.resolveRootDir()

            const args = [
                '-m', 'core.bridge_cli', 'run',
                '--game-dir', opts.gameDir,
                '--mc-version', opts.mcVersion || '1.21.1',
                '--loader', opts.loader || 'neoforge',
                '--loader-version', opts.loaderVersion || '21.1.72',
                '--username', opts.username || 'Player',
                '--uuid', opts.uuid || '',
                '--token', opts.token || '0',
                '--ram', String(opts.ram || 4096)
            ]

            if (opts.manifestUrl) {
                args.push('--manifest-url', opts.manifestUrl)
            }
            if (opts.serverHost) {
                args.push('--server-host', opts.serverHost)
            }
            if (opts.serverPort) {
                args.push('--server-port', String(opts.serverPort))
            }
            if (opts.javaPath) {
                args.push('--java-path', opts.javaPath)
            }

            logger.info(`Starting Python Bridge: ${pythonExe} ${args.join(' ')}`)

            const pyPathEnv = [
                rootDir,
                process.resourcesPath,
                process.env.PYTHONPATH || ''
            ].filter(Boolean).join(process.platform === 'win32' ? ';' : ':')

            const proc = child_process.spawn(pythonExe, args, {
                cwd: rootDir,
                env: {
                    ...process.env,
                    PYTHONPATH: pyPathEnv,
                    PYTHONUNBUFFERED: '1',
                    MINECRAFT_AUTH_TOKEN: opts.token || '0'
                }
            })

            PythonBridge.activeProc = proc
            PythonBridge.gamePid = null

            let lineBuffer = ''

            proc.stdout.on('data', (data) => {
                lineBuffer += data.toString()
                const lines = lineBuffer.split('\n')
                lineBuffer = lines.pop() // keep unended fragment

                for (const line of lines) {
                    const trimmed = line.trim()
                    if (trimmed.startsWith('[BRIDGE_EVENT] ')) {
                        try {
                            const event = JSON.parse(trimmed.substring(15))
                            if (event.type === 'progress' && opts.onProgress) {
                                opts.onProgress(event.percent, event.status)
                            } else if (event.type === 'started') {
                                PythonBridge.gamePid = event.pid
                                if (opts.onStarted) {
                                    opts.onStarted(event.pid, event.version)
                                }
                            } else if (event.type === 'log') {
                                if (opts.onLog) opts.onLog(event.line)
                                logger.info(`[MC] ${event.line}`)
                            } else if (event.type === 'error') {
                                logger.error(`Bridge error: ${event.message}`)
                            }
                        } catch (_err) {
                            logger.warn('Failed to parse bridge event:', trimmed)
                        }
                    } else if (trimmed) {
                        logger.info(`[Bridge] ${trimmed}`)
                    }
                }
            })

            proc.stderr.on('data', (data) => {
                const text = data.toString().trim()
                if (text) {
                    logger.warn(`[Bridge ERR] ${text}`)
                }
            })

            proc.on('close', (code) => {
                PythonBridge.activeProc = null
                PythonBridge.gamePid = null
                logger.info(`Python bridge exited with code ${code}`)
                if (code === 0) {
                    resolve(code)
                } else {
                    reject(new Error(`Minecraft process or bridge exited with error code ${code}`))
                }
            })

            proc.on('error', (err) => {
                PythonBridge.activeProc = null
                PythonBridge.gamePid = null
                logger.error('Failed to spawn Python bridge process:', err)
                if (err.code === 'ENOENT') {
                    reject(new Error(`Не удалось найти интерпретатор Python (${pythonExe}). Убедитесь, что Python 3.10+ установлен и добавлен в PATH, либо укажите переменную окружения LOWUNCHER_PYTHON.`))
                } else {
                    reject(err)
                }
            })
        })
    }
}

module.exports = PythonBridge
