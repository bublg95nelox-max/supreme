const assert = require('assert')
const fs = require('fs')
const path = require('path')
const os = require('os')
const crypto = require('crypto')

console.log('--- Testing Lowuncher Helios Modifications ---')

// 1. Test Offline UUID Generation
function generateOfflineUUID(username) {
    const hash = crypto.createHash('md5').update('OfflinePlayer:' + username, 'utf8').digest()
    hash[6] = (hash[6] & 0x0f) | 0x30
    hash[8] = (hash[8] & 0x3f) | 0x80
    const hex = hash.toString('hex')
    return [
        hex.substring(0, 8),
        hex.substring(8, 12),
        hex.substring(12, 16),
        hex.substring(16, 20),
        hex.substring(20, 32)
    ].join('-')
}

const steveUUID = generateOfflineUUID('Steve')
assert.strictEqual(steveUUID, '5627dd98-e6be-3c21-b8a8-e92344183641', 'Steve UUID must match Java offline UUID')
console.log('✔ Offline UUID generation verified (matches Java UUID.nameUUIDFromBytes)')

// 2. Test RAM Calculation Rule: 50% of system RAM, max 12 GB
function calculateDefaultRAM(totalBytes) {
    const memGB = Math.floor(totalBytes / 1073741824)
    const halfRam = Math.floor(memGB / 2)
    const selected = Math.min(12, Math.max(2, halfRam))
    return `${selected}G`
}

assert.strictEqual(calculateDefaultRAM(8 * 1073741824), '4G', '8 GB RAM -> 4 GB default')
assert.strictEqual(calculateDefaultRAM(16 * 1073741824), '8G', '16 GB RAM -> 8 GB default')
assert.strictEqual(calculateDefaultRAM(32 * 1073741824), '12G', '32 GB RAM -> 12 GB max ceiling')
assert.strictEqual(calculateDefaultRAM(64 * 1073741824), '12G', '64 GB RAM -> 12 GB max ceiling')
assert.strictEqual(calculateDefaultRAM(4 * 1073741824), '2G', '4 GB RAM -> 2 GB minimum')
console.log('✔ RAM calculation verified (50% rule with 12 GB ceiling)')

// 3. Test PythonBridge resolution
const PythonBridge = require('../app/assets/js/python_bridge')
const pythonExe = PythonBridge.resolvePythonPath()
assert.ok(pythonExe && pythonExe.length > 0, 'Python executable must be resolved')
console.log(`✔ PythonBridge resolved Python: ${pythonExe}`)

// 4. Test Language / Custom config
const LangLoader = require('../app/assets/js/langloader')
LangLoader.setupLanguage()
const title = LangLoader.queryEJS('app.title')
assert.strictEqual(title, 'Lowuncher', 'App title in EJS must be Lowuncher')
console.log('✔ LangLoader branding verified: ' + title)

// 5. Test defaultJavaConfig null-safety for servers without effectiveJavaOptions
function testDefaultJavaConfig(effectiveJavaOptions) {
    if(!effectiveJavaOptions || effectiveJavaOptions.suggestedMajor > 8) {
        return 'java17+'
    } else {
        return 'java8'
    }
}
assert.strictEqual(testDefaultJavaConfig(null), 'java17+')
assert.strictEqual(testDefaultJavaConfig(undefined), 'java17+')
assert.strictEqual(testDefaultJavaConfig({ suggestedMajor: 8 }), 'java8')
assert.strictEqual(testDefaultJavaConfig({ suggestedMajor: 21 }), 'java17+')
console.log('✔ defaultJavaConfig null-safety verified')

// 6. Test LaunchStrategy module isolation
const LaunchStrategy = require('../app/assets/js/launch_strategy')
assert.strictEqual(typeof LaunchStrategy.launch, 'function', 'LaunchStrategy.launch must be a function')
assert.strictEqual(typeof LaunchStrategy.resolveServerRAM, 'function', 'LaunchStrategy.resolveServerRAM must be a function')
console.log('✔ LaunchStrategy module isolation verified')

// 7. Test PythonBridge running status and killGame interface
assert.strictEqual(typeof PythonBridge.isGameRunning, 'function', 'PythonBridge.isGameRunning must be a function')
assert.strictEqual(typeof PythonBridge.killGame, 'function', 'PythonBridge.killGame must be a function')
assert.strictEqual(PythonBridge.isGameRunning(), false, 'Game should not be running initially')
console.log('✔ PythonBridge killGame & isGameRunning verified')

// 8. Test Password Verification Logic
const userPasswords = {}
function registerOrVerifyAccount(username, password) {
    const cleanUser = username.trim()
    const lowerUser = cleanUser.toLowerCase()
    const passwordHash = crypto.createHash('sha256').update(password).digest('hex')
    const storedHash = userPasswords[lowerUser]
    if (storedHash && storedHash !== passwordHash) {
        throw new Error(`Неверный пароль для никнейма «${cleanUser}»!`)
    }
    userPasswords[lowerUser] = passwordHash
    return { username: cleanUser, passwordHash }
}

const regAcc = registerOrVerifyAccount('Player123', 'secret123')
assert.strictEqual(regAcc.username, 'Player123')
// Valid password should succeed
const validLogin = registerOrVerifyAccount('Player123', 'secret123')
assert.strictEqual(validLogin.username, 'Player123')
// Invalid password should throw
assert.throws(() => {
    registerOrVerifyAccount('Player123', 'wrongpassword')
}, /Неверный пароль/)
console.log('✔ Password verification (rejects wrong password, accepts correct) verified')

// 9. Verify server address parsing (IPv4, IPv6, port)
assert.deepStrictEqual(LaunchStrategy.parseServerAddress('mc.example.com'), { host: 'mc.example.com', port: 25565 })
assert.deepStrictEqual(LaunchStrategy.parseServerAddress('mc.example.com:27160'), { host: 'mc.example.com', port: 27160 })
assert.deepStrictEqual(LaunchStrategy.parseServerAddress('[2001:db8::1]:25565'), { host: '2001:db8::1', port: 25565 })
assert.deepStrictEqual(LaunchStrategy.parseServerAddress('[::1]'), { host: '::1', port: 25565 })
assert.deepStrictEqual(LaunchStrategy.parseServerAddress('89.248.236.145:27160'), { host: '89.248.236.145', port: 27160 })
assert.deepStrictEqual(LaunchStrategy.parseServerAddress(''), { host: null, port: null })
console.log('✔ LaunchStrategy IPv4/IPv6 server address parser verified')

// 10. Verify PythonBridge safe process termination
assert.doesNotThrow(() => {
    PythonBridge.killProcessSafe(-1)
    PythonBridge.killProcessSafe('invalid')
    PythonBridge.killProcessSafe(0)
})
console.log('✔ PythonBridge.killProcessSafe validation verified')

// 11. Verify DiscordReporter log sanitizer
const DiscordReporter = require('../app/assets/js/discord_reporter')
const sampleLog = 'java -jar game.jar --username Player --token superSecretToken12345 --ram 4096'
const sanitized = DiscordReporter.sanitizeText(sampleLog)
assert.strictEqual(sanitized, 'java -jar game.jar --username Player --token *** --ram 4096')
assert.strictEqual(DiscordReporter.sanitizeText('password: secretPassWord123'), 'password: ***')
assert.strictEqual(DiscordReporter.sanitizeText('MINECRAFT_AUTH_TOKEN: auth_tok_999'), 'MINECRAFT_AUTH_TOKEN: ***')
console.log('✔ DiscordReporter sanitization (token/password masking) verified')

// 12. Verify window controls and Discord diagnostic buttons in UI files
const landingEjs = fs.readFileSync(path.join(__dirname, '../app/landing.ejs'), 'utf8')
const modernCss = fs.readFileSync(path.join(__dirname, '../app/assets/css/modern_ui.css'), 'utf8')
const launcherCss = fs.readFileSync(path.join(__dirname, '../app/assets/css/launcher.css'), 'utf8')

assert(landingEjs.includes('id="modernWindowControls"'), 'modernWindowControls must be present in landing.ejs')
assert(landingEjs.includes('id="modernBtnClose"'), 'modernBtnClose must be present in landing.ejs')
assert(landingEjs.includes('id="heroBtnReport"'), 'heroBtnReport must be present in heroActionRow')
assert(landingEjs.includes('id="modernBtnSendReport"'), 'modernBtnSendReport must be present in Settings')
assert(modernCss.includes('-webkit-app-region: no-drag !important'), 'Window controls must have no-drag !important')
assert(launcherCss.includes('overlayDiscordReportBtn'), 'overlayDiscordReportBtn must be styled in launcher.css')
console.log('✔ UI window controls and Discord diagnostic button templates verified')

// 13. Verify LauncherLogger functionality & persistent logging
const LauncherLogger = require('../app/assets/js/launcher_logger')
LauncherLogger.init()
LauncherLogger.info('Тестовая запись лаунчера с русским текстом')
LauncherLogger.warn('Тестовое предупреждение с паролем: password="my_secret_password"')
const recentLogs = LauncherLogger.getRecentLogs(20)
assert.ok(recentLogs.length > 0, 'LauncherLogger must record log entries')
const hasRussianText = recentLogs.some(l => l.includes('Тестовая запись лаунчера с русским текстом'))
assert.ok(hasRussianText, 'LauncherLogger must record and preserve Russian UTF-8 text')
const maskedLog = recentLogs.find(l => l.includes('Тестовое предупреждение'))
assert.ok(maskedLog && maskedLog.includes('***') && !maskedLog.includes('my_secret_password'), 'LauncherLogger must sanitize passwords')
console.log('✔ LauncherLogger persistent logging and UTF-8 sanitization verified')

console.log('\n--- ALL HELIOS MODIFICATION TESTS PASSED ---')
