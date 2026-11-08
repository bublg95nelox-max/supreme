/**
 * Script for login.ejs
 */
// Validation Regexes.
const validUsername         = /^[a-zA-Z0-9_]{1,16}$/
const basicEmail            = /^\S+@\S+\.\S+$/
//const validEmail          = /^(([^<>()\[\]\.,;:\s@\"]+(\.[^<>()\[\]\.,;:\s@\"]+)*)|(\".+\"))@(([^<>()[\]\.,;:\s@\"]+\.)+[^<>()[\]\.,;:\s@\"]{2,})$/i

// Login Elements
const loginCancelContainer  = document.getElementById('loginCancelContainer')
const loginCancelButton     = document.getElementById('loginCancelButton')
const loginEmailError       = document.getElementById('loginEmailError')
const loginUsername         = document.getElementById('loginUsername')
const loginPasswordError    = document.getElementById('loginPasswordError')
const loginPassword         = document.getElementById('loginPassword')
const checkmarkContainer    = document.getElementById('checkmarkContainer')
const loginRememberOption   = document.getElementById('loginRememberOption')
const loginButton           = document.getElementById('loginButton')
const loginForm             = document.getElementById('loginForm')

// Control variables.
let lu = false, lp = false
let loginIsOffline = true
const validOfflineName = /^[a-zA-Z0-9_]{2,16}$/

function checkLoginValidity(){
    loginDisabled(!(lu && lp))
}

function validateUsernameOffline(value){
    const clean = (value || '').trim()
    if(!clean){
        lu = false
        showError(loginEmailError, 'Введите никнейм')
        checkLoginValidity()
    } else if(!validOfflineName.test(clean)){
        lu = false
        showError(loginEmailError, 'Только буквы A-Z, цифры и _ (2-16 симв.)')
        checkLoginValidity()
    } else {
        loginEmailError.style.opacity = 0
        lu = true
        checkLoginValidity()
    }
}

function validatePasswordOffline(value){
    const clean = (value || '').trim()
    if(!clean){
        lp = false
        showError(loginPasswordError, 'Введите пароль')
        checkLoginValidity()
    } else if(clean.length < 3){
        lp = false
        showError(loginPasswordError, 'Пароль минимум 3 символа')
        checkLoginValidity()
    } else {
        loginPasswordError.style.opacity = 0
        lp = true
        checkLoginValidity()
    }
}

function setupOfflineMode(isOffline){
    loginIsOffline = isOffline
    const passwordContainer = loginPassword ? loginPassword.closest('.loginFieldContainer') : null
    const loginOptionsElem = document.getElementById('loginOptions')
    const loginDisclaimerElem = document.getElementById('loginDisclaimer')
    const loginSubheaderElem = document.getElementById('loginSubheader')

    if(passwordContainer) $(passwordContainer).show()

    if(isOffline){
        if(loginOptionsElem) $(loginOptionsElem).hide()
        if(loginDisclaimerElem) $(loginDisclaimerElem).hide()
        if(loginSubheaderElem) loginSubheaderElem.innerText = 'ВХОД / РЕГИСТРАЦИЯ'
        if(loginUsername) loginUsername.placeholder = 'Введите никнейм...'
        if(loginPassword) loginPassword.placeholder = 'Введите пароль (или PIN)...'
        validateUsernameOffline(loginUsername.value)
        validatePasswordOffline(loginPassword.value)
    } else {
        if(loginOptionsElem) $(loginOptionsElem).show()
        if(loginDisclaimerElem) $(loginDisclaimerElem).show()
        if(loginSubheaderElem) loginSubheaderElem.innerText = Lang.queryJS('login.loginSubheader')
        if(loginUsername) loginUsername.placeholder = Lang.queryJS('login.loginEmailPlaceholder')
        if(loginPassword) loginPassword.placeholder = Lang.queryJS('login.loginPasswordPlaceholder')
        validateEmail(loginUsername.value)
        validatePassword(loginPassword.value)
    }
}


/**
 * Show a login error.
 * 
 * @param {HTMLElement} element The element on which to display the error.
 * @param {string} value The error text.
 */
function showError(element, value){
    element.innerHTML = value
    element.style.opacity = 1
}

/**
 * Shake a login error to add emphasis.
 * 
 * @param {HTMLElement} element The element to shake.
 */
function shakeError(element){
    if(element.style.opacity == 1){
        element.classList.remove('shake')
        void element.offsetWidth
        element.classList.add('shake')
    }
}

/**
 * Validate that an email field is neither empty nor invalid.
 * 
 * @param {string} value The email value.
 */
function validateEmail(value){
    if(value){
        if(!basicEmail.test(value) && !validUsername.test(value)){
            showError(loginEmailError, Lang.queryJS('login.error.invalidValue'))
            loginDisabled(true)
            lu = false
        } else {
            loginEmailError.style.opacity = 0
            lu = true
            if(lp){
                loginDisabled(false)
            }
        }
    } else {
        lu = false
        showError(loginEmailError, Lang.queryJS('login.error.requiredValue'))
        loginDisabled(true)
    }
}

/**
 * Validate that the password field is not empty.
 * 
 * @param {string} value The password value.
 */
function validatePassword(value){
    if(value){
        loginPasswordError.style.opacity = 0
        lp = true
        if(lu){
            loginDisabled(false)
        }
    } else {
        lp = false
        showError(loginPasswordError, Lang.queryJS('login.error.invalidValue'))
        loginDisabled(true)
    }
}

// Emphasize errors with shake when focus is lost.
loginUsername.addEventListener('focusout', (e) => {
    if(loginIsOffline){
        validateUsernameOffline(e.target.value)
    } else {
        validateEmail(e.target.value)
    }
    shakeError(loginEmailError)
})
loginPassword.addEventListener('focusout', (e) => {
    if(loginIsOffline){
        validatePasswordOffline(e.target.value)
    } else {
        validatePassword(e.target.value)
    }
    shakeError(loginPasswordError)
})

// Validate input for each field.
loginUsername.addEventListener('input', (e) => {
    if(loginIsOffline){
        validateUsernameOffline(e.target.value)
    } else {
        validateEmail(e.target.value)
    }
})
loginPassword.addEventListener('input', (e) => {
    if(loginIsOffline){
        validatePasswordOffline(e.target.value)
    } else {
        validatePassword(e.target.value)
    }
})

loginUsername.addEventListener('keydown', (e) => {
    if(e.key === 'Enter' && !loginButton.disabled){
        loginButton.click()
    }
})
loginPassword.addEventListener('keydown', (e) => {
    if(e.key === 'Enter' && !loginButton.disabled){
        loginButton.click()
    }
})

/**
 * Enable or disable the login button.
 * 
 * @param {boolean} v True to enable, false to disable.
 */
function loginDisabled(v){
    if(loginButton.disabled !== v){
        loginButton.disabled = v
    }
}

/**
 * Enable or disable loading elements.
 * 
 * @param {boolean} v True to enable, false to disable.
 */
function loginLoading(v){
    if(v){
        loginButton.setAttribute('loading', v)
        loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.login'), Lang.queryJS('login.loggingIn'))
    } else {
        loginButton.removeAttribute('loading')
        loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.loggingIn'), Lang.queryJS('login.login'))
    }
}

/**
 * Enable or disable login form.
 * 
 * @param {boolean} v True to enable, false to disable.
 */
function formDisabled(v){
    loginDisabled(v)
    loginCancelButton.disabled = v
    loginUsername.disabled = v
    loginPassword.disabled = v
    if(v){
        checkmarkContainer.setAttribute('disabled', v)
    } else {
        checkmarkContainer.removeAttribute('disabled')
    }
    loginRememberOption.disabled = v
}

let loginViewOnSuccess = VIEWS.landing
let loginViewOnCancel = VIEWS.settings
let loginViewCancelHandler

function loginCancelEnabled(val){
    if(val){
        $(loginCancelContainer).show()
    } else {
        $(loginCancelContainer).hide()
    }
}

loginCancelButton.onclick = (e) => {
    switchView(getCurrentView(), loginViewOnCancel, 500, 500, () => {
        loginUsername.value = ''
        loginPassword.value = ''
        loginCancelEnabled(false)
        if(loginViewCancelHandler != null){
            loginViewCancelHandler()
            loginViewCancelHandler = null
        }
    })
}

// Disable default form behavior.
loginForm.onsubmit = () => { return false }

// Bind login button behavior.
loginButton.addEventListener('click', () => {
    // Disable form.
    formDisabled(true)

    // Show loading stuff.
    loginLoading(true)

    if(loginIsOffline){
        const nick = (loginUsername.value || '').trim()
        const pass = (loginPassword.value || '').trim()
        const RESTRICTED_NICKS = ['9lame', 'lllllame']
        if (RESTRICTED_NICKS.includes(nick.toLowerCase()) && pass !== '6233') {
            loginLoading(false)
            setOverlayContent('Доступ ограничен', 'Этот ник зарезервирован владельцем. Введите PIN (6233).', Lang.queryJS('login.tryAgain'))
            setOverlayHandler(() => {
                formDisabled(false)
                toggleOverlay(false)
            })
            toggleOverlay(true)
            return
        }

        AuthManager.addCommunityAccount(nick, pass).then((value) => {
            updateSelectedAccount(value)
            loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.loggingIn'), Lang.queryJS('login.success'))
            $('.circle-loader').toggleClass('load-complete')
            $('.checkmark').toggle()
            setTimeout(() => {
                switchView(VIEWS.login, loginViewOnSuccess, 500, 500, async () => {
                    if(loginViewOnSuccess === VIEWS.settings){
                        await prepareSettings()
                    }
                    loginViewOnSuccess = VIEWS.landing
                    loginCancelEnabled(false)
                    loginViewCancelHandler = null
                    loginUsername.value = ''
                    loginPassword.value = ''
                    $('.circle-loader').toggleClass('load-complete')
                    $('.checkmark').toggle()
                    loginLoading(false)
                    loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.success'), Lang.queryJS('login.login'))
                    formDisabled(false)
                })
            }, 500)
        }).catch((err) => {
            loginLoading(false)
            setOverlayContent('Ошибка входа', err.message || 'Не удалось войти', Lang.queryJS('login.tryAgain'))
            setOverlayHandler(() => {
                formDisabled(false)
                toggleOverlay(false)
            })
            toggleOverlay(true)
        })
        return
    }

    AuthManager.addMojangAccount(loginUsername.value, loginPassword.value).then((value) => {
        updateSelectedAccount(value)
        loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.loggingIn'), Lang.queryJS('login.success'))
        $('.circle-loader').toggleClass('load-complete')
        $('.checkmark').toggle()
        setTimeout(() => {
            switchView(VIEWS.login, loginViewOnSuccess, 500, 500, async () => {
                // Temporary workaround
                if(loginViewOnSuccess === VIEWS.settings){
                    await prepareSettings()
                }
                loginViewOnSuccess = VIEWS.landing // Reset this for good measure.
                loginCancelEnabled(false) // Reset this for good measure.
                loginViewCancelHandler = null // Reset this for good measure.
                loginUsername.value = ''
                loginPassword.value = ''
                $('.circle-loader').toggleClass('load-complete')
                $('.checkmark').toggle()
                loginLoading(false)
                loginButton.innerHTML = loginButton.innerHTML.replace(Lang.queryJS('login.success'), Lang.queryJS('login.login'))
                formDisabled(false)
            })
        }, 1000)
    }).catch((displayableError) => {
        loginLoading(false)

        let actualDisplayableError
        if(isDisplayableError(displayableError)) {
            msftLoginLogger.error('Error while logging in.', displayableError)
            actualDisplayableError = displayableError
        } else {
            // Uh oh.
            msftLoginLogger.error('Unhandled error during login.', displayableError)
            actualDisplayableError = Lang.queryJS('login.error.unknown')
        }

        setOverlayContent(actualDisplayableError.title, actualDisplayableError.desc, Lang.queryJS('login.tryAgain'))
        setOverlayHandler(() => {
            formDisabled(false)
            toggleOverlay(false)
        })
        toggleOverlay(true)
    })

})