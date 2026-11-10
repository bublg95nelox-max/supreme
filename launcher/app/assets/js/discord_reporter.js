const http = require('http')
const https = require('https')
const tls = require('tls')
const os = require('os')
const got = require('got')
const { LoggerUtil } = require('helios-core')
const LauncherLogger = require('./launcher_logger')

const logger = LoggerUtil.getLogger('DiscordReporter')

const WORKER_URL = 'https://crash-assistant-discord-relay.crash-assistant-discord-relay.workers.dev'
const DISCORD_WEBHOOK_URL = 'https://discord.com/api/webhooks/1569726539153543238/_ee4jYHOUa_VnbIQVHcrlDaD8qAnE2BUgj_hwown1bkjhjqfFSDTRDeVdnsPh1wHv0Lu'

let ConfigManager = null
function getConfigManager() {
    if (!ConfigManager) {
        try {
            ConfigManager = require('./configmanager')
        } catch (_e) {}
    }
    return ConfigManager
}

class DiscordReporter {

    /**
     * Sanitizes sensitive information (tokens, passwords, keys) from log text.
     * @param {string} text 
     * @returns {string}
     */
    static sanitizeText(text) {
        if (!text || typeof text !== 'string') return ''
        return text
            .replace(/(--token\s+)[^\s]+/gi, '$1***')
            .replace(/(MINECRAFT_AUTH_TOKEN\s*[:=]\s*)[^\s]+/gi, '$1***')
            .replace(/(token\s*[:=]\s*["']?)[a-f0-9_\-]+(["']?)/gi, '$1***$2')
            .replace(/(password\s*[:=]\s*["']?)[^\s"']+(["']?)/gi, '$1***$2')
            .replace(/(api[_-]?key\s*[:=]\s*["']?)[^\s"']+(["']?)/gi, '$1***$2')
    }

    /**
     * Uploads log content to mclo.gs for convenient web viewing.
     * @param {string} logContent 
     * @returns {Promise<string|null>} URL on mclo.gs or null on failure
     */
    static async uploadLogToMclogs(logContent) {
        if (!logContent || typeof logContent !== 'string' || !logContent.trim()) {
            return null
        }
        const sanitized = DiscordReporter.sanitizeText(logContent)
        try {
            const resp = await got.post('https://api.mclo.gs/1/log', {
                form: { content: sanitized },
                responseType: 'json',
                timeout: 8000
            })
            if (resp.body && resp.body.success && resp.body.url) {
                logger.info(`Log successfully uploaded to mclo.gs: ${resp.body.url}`)
                return resp.body.url
            }
        } catch (err) {
            logger.warn('Failed to upload log directly to mclo.gs:', err.message)
        }

        // Fallback: try proxy tunnel if configured
        const proxyCandidate = process.env.HTTPS_PROXY || process.env.HTTP_PROXY || 'http://127.0.0.1:10809'
        try {
            const proxyUrl = new URL(proxyCandidate)
            const formBody = 'content=' + encodeURIComponent(sanitized)
            const proxyRes = await new Promise((resolve, reject) => {
                const connectReq = http.request({
                    host: proxyUrl.hostname || '127.0.0.1',
                    port: Number.parseInt(proxyUrl.port, 10) || 10809,
                    method: 'CONNECT',
                    path: 'api.mclo.gs:443',
                    timeout: 5000
                })
                connectReq.on('connect', (res, socket) => {
                    if (res.statusCode !== 200) return reject(new Error(`Proxy status ${res.statusCode}`))
                    const tlsSocket = tls.connect({ socket, servername: 'api.mclo.gs' }, () => {
                        const req = https.request({
                            host: 'api.mclo.gs',
                            path: '/1/log',
                            method: 'POST',
                            createConnection: () => tlsSocket,
                            headers: {
                                'Content-Type': 'application/x-www-form-urlencoded',
                                'Content-Length': Buffer.byteLength(formBody),
                                'User-Agent': 'Lowuncher/1.0.6'
                            },
                            timeout: 8000
                        }, (resp) => {
                            let out = ''
                            resp.on('data', chunk => out += chunk)
                            resp.on('end', () => resolve({ statusCode: resp.statusCode, body: out }))
                        })
                        req.on('error', reject)
                        req.write(formBody)
                        req.end()
                    })
                    tlsSocket.on('error', reject)
                })
                connectReq.on('error', reject)
                connectReq.on('timeout', () => connectReq.destroy(new Error('Proxy connect timed out')))
                connectReq.end()
            })
            if (proxyRes && proxyRes.statusCode === 200) {
                const parsed = JSON.parse(proxyRes.body)
                if (parsed.success && parsed.url) {
                    logger.info(`Log successfully uploaded to mclo.gs via proxy: ${parsed.url}`)
                    return parsed.url
                }
            }
        } catch (_proxyErr) {}

        return null
    }

    /**
     * Performs an HTTP POST over an HTTP proxy tunnel (CONNECT).
     * @param {string} targetUrl 
     * @param {object} payload 
     * @param {string} proxyHost 
     * @param {number} proxyPort 
     */
    static postViaProxy(targetUrl, payload, proxyHost = '127.0.0.1', proxyPort = 10809) {
        return new Promise((resolve, reject) => {
            const target = new URL(targetUrl)
            const postData = JSON.stringify(payload)

            const connectReq = http.request({
                host: proxyHost,
                port: proxyPort,
                method: 'CONNECT',
                path: `${target.hostname}:${target.port || 443}`,
                timeout: 5000
            })

            connectReq.on('connect', (res, socket) => {
                if (res.statusCode !== 200) {
                    return reject(new Error(`Proxy CONNECT error: status ${res.statusCode}`))
                }
                const tlsSocket = tls.connect({
                    socket,
                    servername: target.hostname
                }, () => {
                    const req = https.request({
                        host: target.hostname,
                        path: target.pathname + target.search,
                        method: 'POST',
                        createConnection: () => tlsSocket,
                        headers: {
                            'Content-Type': 'application/json',
                            'Content-Length': Buffer.byteLength(postData),
                            'User-Agent': 'Lowuncher/1.0.6'
                        },
                        timeout: 8000
                    }, (resp) => {
                        let out = ''
                        resp.on('data', chunk => out += chunk)
                        resp.on('end', () => {
                            resolve({ statusCode: resp.statusCode, body: out })
                        })
                    })
                    req.on('error', reject)
                    req.write(postData)
                    req.end()
                })
                tlsSocket.on('error', reject)
            })

            connectReq.on('error', reject)
            connectReq.on('timeout', () => {
                connectReq.destroy(new Error('Proxy connect timed out'))
            })
            connectReq.end()
        })
    }

    /**
     * Dispatches a JSON payload to an HTTPS endpoint, with automatic proxy fallback.
     * @param {string} targetUrl 
     * @param {object} payload 
     */
    static async dispatchJson(targetUrl, payload) {
        // 1. Try direct HTTPS request first
        try {
            const resp = await got.post(targetUrl, {
                json: payload,
                timeout: 5000,
                headers: { 'User-Agent': 'Lowuncher/1.0.6' }
            })
            return { success: true, body: resp.body }
        } catch (directErr) {
            logger.warn(`Direct request to ${targetUrl} failed: ${directErr.message}. Attempting mirror / proxy fallback...`)
        }

        // 2. If it is a discord webhook and direct failed, try discord webhook mirror
        if (targetUrl.includes('discord.com/api/webhooks/')) {
            const mirrorUrl = targetUrl.replace('discord.com', 'webhook.lewisakura.moe')
            try {
                const resp = await got.post(mirrorUrl, {
                    json: payload,
                    timeout: 5000,
                    headers: { 'User-Agent': 'Lowuncher/1.0.6' }
                })
                return { success: true, body: resp.body }
            } catch (mirrorErr) {
                logger.warn(`Discord mirror request to ${mirrorUrl} failed: ${mirrorErr.message}. Attempting proxy tunnel fallback...`)
            }
        }

        // 3. Try proxy tunnel fallback if available
        const proxyCandidate = process.env.HTTPS_PROXY || process.env.HTTP_PROXY || 'http://127.0.0.1:10809'
        try {
            const proxyUrl = new URL(proxyCandidate)
            const proxyHost = proxyUrl.hostname || '127.0.0.1'
            const proxyPort = Number.parseInt(proxyUrl.port, 10) || 10809
            const proxyRes = await DiscordReporter.postViaProxy(targetUrl, payload, proxyHost, proxyPort)
            if (proxyRes.statusCode >= 200 && proxyRes.statusCode < 300) {
                return { success: true, body: proxyRes.body }
            }
            logger.warn(`Proxy tunnel dispatch returned status ${proxyRes.statusCode}: ${proxyRes.body}`)
        } catch (proxyErr) {
            logger.warn(`Proxy tunnel fallback failed: ${proxyErr.message}`)
        }

        return { success: false, error: 'Network request failed directly and via proxy.' }
    }

    /**
     * Gathers diagnostic details and dispatches a comprehensive error report to Discord.
     * 
     * @param {object} opts
     * @param {string|Error} opts.error Error description or Error instance
     * @param {string[]|string} [opts.logs] Recent log lines
     * @param {string} [opts.title] Custom title
     * @returns {Promise<{ success: boolean, mclogsUrl: string|null, error?: string }>}
     */
    static async sendReport(opts = {}) {
        const errorMsg = opts.error instanceof Error ? opts.error.message : String(opts.error || 'Неизвестная ошибка')
        const cfg = getConfigManager()

        let authUser = null
        try {
            if (cfg && typeof cfg.getSelectedAccount === 'function') {
                authUser = cfg.getSelectedAccount()
            }
        } catch (_e) {}

        const playerName = opts.username || (authUser ? authUser.displayName : 'Игрок')
        const playerUuid = opts.uuid || (authUser ? authUser.uuid : null)

        let logLines = []

        // 1. Always gather persistent launcher logs (disk + buffer)
        try {
            const diskLogs = LauncherLogger.getRecentLogs(500)
            if (diskLogs && diskLogs.length > 0) {
                logLines.push('=== LOWUNCHER LAUNCHER.LOG ===')
                logLines.push(...diskLogs)
            }
        } catch (_logErr) {}

        // 2. Append runtime logs passed by caller (e.g. from UI recentLaunchLogs or instance latest.log)
        if (Array.isArray(opts.logs) && opts.logs.length > 0) {
            logLines.push('\n=== ADDITIONAL / INSTANCE LOGS ===')
            logLines.push(...opts.logs.map(l => String(l)))
        } else if (typeof opts.logs === 'string' && opts.logs.trim()) {
            logLines.push('\n=== ADDITIONAL / INSTANCE LOGS ===')
            logLines.push(opts.logs.trim())
        }

        const rawLogText = logLines.join('\n')
        const mclogsUrl = await DiscordReporter.uploadLogToMclogs(rawLogText)

        const totalRamGB = Math.round(os.totalmem() / (1024 * 1024 * 1024))
        const freeRamGB = Math.round(os.freemem() / (1024 * 1024 * 1024))
        const sysDesc = `${os.type()} ${os.release()} (${os.arch()}), ОЗУ: ${totalRamGB} ГБ (свободно ~${freeRamGB} ГБ)`

        // Recent log snippet (last 25 lines)
        const logSnippet = logLines.slice(-25).join('\n').trim()

        // 1. Prepare structured payload for Cloudflare Worker
        const workerPayload = {
            modpack: 'Supreme 1.21.1',
            player: playerName,
            uuid: playerUuid,
            minecraft: '1.21.1',
            loader: 'NeoForge 21.1.265',
            os: sysDesc,
            java: 'Java 21',
            ram: `${totalRamGB} ГБ`,
            crashReasons: [
                errorMsg
            ],
            problematicFrame: logSnippet ? DiscordReporter.sanitizeText(logSnippet) : null,
            logs: mclogsUrl ? [
                {
                    name: 'Лог запуска лаунчера',
                    fileName: 'launcher.log',
                    url: mclogsUrl
                }
            ] : []
        }

        logger.info(`Dispatching crash report to Discord for player "${playerName}"...`)

        // 1. First attempt: Cloudflare Worker relay
        let result = await DiscordReporter.dispatchJson(WORKER_URL, workerPayload)
        if (result.success) {
            logger.info('Crash report successfully delivered via Cloudflare Worker!')
            return { success: true, mclogsUrl }
        }

        // 2. Second attempt: Direct Discord Webhook fallback
        logger.warn('Cloudflare Worker relay failed, attempting direct Discord Webhook fallback...')
        const directDiscordPayload = {
            content: `🚨 **Сбой запуска Lowuncher** у игрока **${playerName}**`,
            embeds: [
                {
                    title: `🚨 Сбой в сборке Supreme 1.21.1`,
                    color: 0xED4245,
                    fields: [
                        { name: '👤 Игрок', value: `\`${playerName}\`${playerUuid ? ` (\`${playerUuid}\`)` : ''}`, inline: true },
                        { name: '⚙️ Версии', value: '`1.21.1` / `NeoForge 21.1.265`', inline: true },
                        { name: '🖥️ Система', value: `\`${sysDesc}\``, inline: false },
                        { name: '⚠️ Ошибка', value: `\`\`\`\n${errorMsg.slice(0, 950)}\n\`\`\``, inline: false },
                        ...(mclogsUrl ? [{ name: '📋 Полный лог (mclo.gs)', value: `• **[📄 Открыть лог в браузере](${mclogsUrl})**`, inline: false }] : []),
                        ...(logSnippet ? [{ name: '📜 Хвост лога', value: `\`\`\`\n${DiscordReporter.sanitizeText(logSnippet).slice(0, 900)}\n\`\`\``, inline: false }] : [])
                    ],
                    footer: { text: 'Lowuncher Diagnostic Reporter' },
                    timestamp: new Date().toISOString()
                }
            ]
        }

        result = await DiscordReporter.dispatchJson(DISCORD_WEBHOOK_URL, directDiscordPayload)
        if (result.success) {
            logger.info('Crash report successfully delivered via direct Discord Webhook!')
            return { success: true, mclogsUrl }
        }

        logger.error('Failed to deliver crash report to Discord through all available methods.')
        return { success: false, error: 'Не удалось связаться с Discord сервером.', mclogsUrl }
    }
}

module.exports = DiscordReporter
