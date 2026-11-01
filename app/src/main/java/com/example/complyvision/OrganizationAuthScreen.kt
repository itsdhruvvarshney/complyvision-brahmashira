package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class OrgAuthMode { LOGIN, FORGOT }

/**
 * Organisation Pre-Verification entry. Organisations register on the ComplyVision website; here they can only
 * log in, or ask for a one-time password reset link that the ComplyVision Support Team emails them.
 */
@Composable
fun OrganizationAuthScreen(onBack: () -> Unit, onLoggedIn: (OrgSession) -> Unit) {
    var modeName by rememberSaveable { mutableStateOf(OrgAuthMode.LOGIN.name) }
    val mode = OrgAuthMode.valueOf(modeName)
    var busy by remember { mutableStateOf(false) }

    BackHandler(enabled = mode == OrgAuthMode.FORGOT && !busy) { modeName = OrgAuthMode.LOGIN.name }

    Box(Modifier.fillMaxSize().background(portalPageBrush())) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()) {
            OrgHero(forgot = mode == OrgAuthMode.FORGOT, onBack = {
                if (mode == OrgAuthMode.FORGOT) modeName = OrgAuthMode.LOGIN.name else onBack()
            }, backEnabled = !busy)

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val dir = if (targetState == OrgAuthMode.FORGOT) 1 else -1
                    (fadeIn(tween(260, 80)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 6 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -dir * it / 6 })
                },
                label = "orgAuth"
            ) { m ->
                when (m) {
                    OrgAuthMode.LOGIN -> LoginForm(onForgot = { modeName = OrgAuthMode.FORGOT.name }, onLoggedIn = onLoggedIn, onBusy = { busy = it })
                    OrgAuthMode.FORGOT -> ForgotForm(onBackToLogin = { modeName = OrgAuthMode.LOGIN.name }, onBusy = { busy = it })
                }
            }
        }
    }
}

@Composable
private fun OrgHero(forgot: Boolean, onBack: () -> Unit, backEnabled: Boolean) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        PortalTopBar(onBack = onBack, backEnabled = backEnabled)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(224.dp)) {
            AnimatedContent(forgot, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "orgArt",
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.58f)) { f ->
                Image(
                    painter = painterResource(if (f) R.drawable.org_hero_forgot else R.drawable.org_hero_login),
                    contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.42f to PortalPageTop, 0.66f to PortalPageTop.copy(alpha = 0f))))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 20.dp).fillMaxWidth(0.6f)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFDCEBFF)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AIconView(AIcon.BUILDING, CBlue, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Organisation Portal", color = CBlue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(forgot, transitionSpec = { fadeIn(tween(300, 60)) togetherWith fadeOut(tween(160)) }, label = "orgTitle") { f ->
                    Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = CNavy)) { append(if (f) "Forgot\n" else "Organization\n") }
                                withStyle(SpanStyle(color = CBlue)) { append(if (f) "Password?" else "Login") }
                            },
                            fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp, letterSpacing = (-0.8).sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (f) "Reset your organization account password by verifying your details."
                            else "Access your business account to run BIS and Pre-FSSAI verifications.",
                            fontSize = 13.sp, lineHeight = 17.sp, color = CMuted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0)),
        shape = RoundedCornerShape(30.dp), color = Color.White.copy(alpha = 0.82f), border = BorderStroke(1.dp, Color.White)
    ) { Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun CaptchaRow(
    captcha: String, input: String, error: String?, enabled: Boolean, focus: FocusRequester,
    onInput: (String) -> Unit, onRefresh: () -> Unit, onDone: () -> Unit, onFocusLost: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptchaImage(captcha, Modifier.weight(0.9f))
        Box(
            Modifier.size(54.dp).shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0))
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFFEAF2FF)).border(1.dp, CBorder, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Refresh captcha" }.clickable(enabled = enabled, role = Role.Button) { onRefresh() },
            contentAlignment = Alignment.Center
        ) { GlyphIcon(Glyph.REFRESH, CBlue, Modifier.size(24.dp)) }
        AuthField(
            value = input,
            onChange = {
                val cleaned = it.filter { c -> c.isLetterOrDigit() }.uppercase()
                if (cleaned.length <= LoginValidator.CAPTCHA_LENGTH) onInput(cleaned)
            },
            label = null, placeholder = "Enter Captcha", error = error, enabled = enabled,
            leading = { GlyphIcon(Glyph.SHIELD_CHECK, CBlue, Modifier.size(22.dp)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }), onFocusLost = onFocusLost, compact = true,
            modifier = Modifier.weight(1.25f).focusRequester(focus)
        )
    }
}

// ---------------------------------------------------------------------------
// Login
// ---------------------------------------------------------------------------

@Composable
private fun LoginForm(onForgot: () -> Unit, onLoggedIn: (OrgSession) -> Unit, onBusy: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val uri = LocalUriHandler.current

    var name by rememberSaveable { mutableStateOf("") }
    var orgId by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf(LoginValidator.newCaptcha()) }
    var captchaInput by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(setOf<String>()) }
    var submitAttempted by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var cooldown by rememberSaveable { mutableIntStateOf(0) }

    val nameF = remember { FocusRequester() }
    val idF = remember { FocusRequester() }
    val emailF = remember { FocusRequester() }
    val pwF = remember { FocusRequester() }
    val capF = remember { FocusRequester() }

    fun show(k: String) = submitAttempted || k in touched
    val nameErr = if (show("name")) OrgValidator.nameError(name) else null
    val idErr = if (show("id")) OrgValidator.idError(orgId) else null
    val emailErr = if (show("email")) ConsumerValidator.emailError(email) else null
    val pwErr = if (show("pw")) ConsumerValidator.passwordError(password, false) else null
    val capErr = if (show("cap")) LoginValidator.captchaError(captchaInput, captcha) else null

    LaunchedEffect(cooldown > 0) { while (cooldown > 0) { delay(1000); cooldown -= 1 } }
    LaunchedEffect(submitting) { onBusy(submitting) }

    fun refreshCaptcha() { captcha = LoginValidator.newCaptcha(); captchaInput = ""; touched = touched - "cap" }

    fun submit() {
        if (submitting) return
        submitAttempted = true
        formError = null
        val firstInvalid = when {
            OrgValidator.nameError(name) != null -> nameF
            OrgValidator.idError(orgId) != null -> idF
            ConsumerValidator.emailError(email) != null -> emailF
            ConsumerValidator.passwordError(password, false) != null -> pwF
            LoginValidator.captchaError(captchaInput, captcha) != null -> capF
            else -> null
        }
        if (firstInvalid != null) {
            if (firstInvalid === capF && captchaInput.length >= LoginValidator.CAPTCHA_LENGTH) refreshCaptcha()
            firstInvalid.requestFocus()
            return
        }
        if (cooldown > 0) return
        focusManager.clearFocus()
        keyboard?.hide()
        submitting = true
        scope.launch {
            try {
                when (val r = OrgRepository.login(orgId.trim().uppercase(), LoginValidator.normalizeName(name), email.trim().lowercase(), password)) {
                    is OrgAuthResult.Success -> { onLoggedIn(r.session); return@launch }
                    is OrgAuthResult.Invalid -> {
                        val left = r.attemptsLeft
                        formError = buildString {
                            append("Organization details or password are incorrect.")
                            if (left != null && left in 1..3) append(" $left attempt${if (left == 1) "" else "s"} left before the account is locked.")
                        }
                        password = ""
                    }
                    is OrgAuthResult.Locked -> {
                        val minutes = (r.secondsLeft + 59) / 60
                        formError = "Too many failed attempts. This account is locked for about $minutes minute${if (minutes == 1) "" else "s"}."
                        password = ""
                        cooldown = minOf(r.secondsLeft, 60)
                    }
                    is OrgAuthResult.Inactive -> formError = "This organization account is ${r.status.ifBlank { "not active" }}. Please contact the ComplyVision Support Team."
                }
                refreshCaptcha()
                submitAttempted = false
                touched = emptySet()
            } catch (e: ApiException) {
                formError = e.userMessage()
                refreshCaptcha()
            } finally {
                submitting = false
            }
        }
    }

    Column {
        Staggered(0) {
            FormCard {
                AuthField(
                    value = name, onChange = { if (it.length <= 130) name = it; formError = null },
                    label = "Organization Name", placeholder = "Enter your organization name", error = nameErr, enabled = !submitting,
                    leading = { AIconView(AIcon.BUILDING, CBlue, Modifier.size(26.dp)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { idF.requestFocus() }), onFocusLost = { touched = touched + "name" },
                    modifier = Modifier.focusRequester(nameF)
                )
                AuthField(
                    value = orgId, onChange = { orgId = it.uppercase().filter { c -> !c.isWhitespace() }.take(20); formError = null },
                    label = "Organization ID", placeholder = "Enter your organization ID", error = idErr, enabled = !submitting,
                    leading = { GlyphIcon(Glyph.ID_CARD, CBlue, Modifier.size(26.dp)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { emailF.requestFocus() }), onFocusLost = { touched = touched + "id" },
                    modifier = Modifier.focusRequester(idF)
                )
                AuthField(
                    value = email, onChange = { email = it.filter { c -> !c.isWhitespace() }.take(125); formError = null },
                    label = "Authorized Person Email", placeholder = "Enter registered email address", error = emailErr, enabled = !submitting,
                    leading = { AIconView(AIcon.MAIL, CBlue, Modifier.size(24.dp)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { pwF.requestFocus() }), onFocusLost = { touched = touched + "email" },
                    modifier = Modifier.focusRequester(emailF)
                )
                AuthField(
                    value = password, onChange = { if (it.length <= ConsumerValidator.MAX_PASSWORD) password = it; formError = null },
                    label = "Password", placeholder = "Enter your password", error = pwErr, enabled = !submitting,
                    leading = { GlyphIcon(Glyph.LOCK, CBlue, Modifier.size(24.dp)) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { capF.requestFocus() }), onFocusLost = { touched = touched + "pw" },
                    trailing = {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp))
                                .semantics { contentDescription = if (showPassword) "Hide password" else "Show password" }
                                .clickable(enabled = !submitting, role = Role.Button) { showPassword = !showPassword },
                            contentAlignment = Alignment.Center
                        ) { GlyphIcon(if (showPassword) Glyph.EYE_OFF else Glyph.EYE, CBlue, Modifier.size(24.dp)) }
                    },
                    modifier = Modifier.focusRequester(pwF)
                )
                CaptchaRow(captcha, captchaInput, capErr, !submitting, capF, { captchaInput = it; formError = null }, ::refreshCaptcha, ::submit, { touched = touched + "cap" })

                AnimatedVisibility(formError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ErrorBanner(formError.orEmpty())
                }
                GradientButton("Login", "Signing in…", submitting, cooldown, ::submit)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(CBorder))
                    Text(
                        "Forgot Password?", color = CBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !submitting, role = Role.Button) { onForgot() }.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(CBorder))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val site = BuildConfig.WEBSITE_URL.trim()
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("New organization? Register on the ComplyVision website, then log in here.", fontSize = 12.5.sp, color = CMuted, lineHeight = 16.sp, textAlign = TextAlign.Center)
            if (site.isNotEmpty()) {
                Text(
                    "Open registration page", color = CBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                        try { uri.openUri(site.trimEnd('/') + "/register-organisation.html") } catch (_: Exception) { }
                    }.padding(10.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------------------
// Forgot password
// ---------------------------------------------------------------------------

@Composable
private fun ForgotForm(onBackToLogin: () -> Unit, onBusy: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var orgId by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf(LoginValidator.newCaptcha()) }
    var captchaInput by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(setOf<String>()) }
    var submitAttempted by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var cooldown by rememberSaveable { mutableIntStateOf(0) }

    val idF = remember { FocusRequester() }
    val emailF = remember { FocusRequester() }
    val capF = remember { FocusRequester() }

    fun show(k: String) = submitAttempted || k in touched
    val idErr = if (show("id")) OrgValidator.idError(orgId) else null
    val emailErr = if (show("email")) ConsumerValidator.emailError(email) else null
    val capErr = if (show("cap")) LoginValidator.captchaError(captchaInput, captcha) else null

    LaunchedEffect(cooldown > 0) { while (cooldown > 0) { delay(1000); cooldown -= 1 } }
    LaunchedEffect(submitting) { onBusy(submitting) }

    fun refreshCaptcha() { captcha = LoginValidator.newCaptcha(); captchaInput = ""; touched = touched - "cap" }

    fun submit() {
        if (submitting) return
        submitAttempted = true
        error = null
        val firstInvalid = when {
            OrgValidator.idError(orgId) != null -> idF
            ConsumerValidator.emailError(email) != null -> emailF
            LoginValidator.captchaError(captchaInput, captcha) != null -> capF
            else -> null
        }
        if (firstInvalid != null) {
            if (firstInvalid === capF && captchaInput.length >= LoginValidator.CAPTCHA_LENGTH) refreshCaptcha()
            firstInvalid.requestFocus()
            return
        }
        if (cooldown > 0) return
        focusManager.clearFocus()
        keyboard?.hide()
        submitting = true
        scope.launch {
            try {
                sentTo = OrgRepository.requestPasswordReset(orgId.trim().uppercase(), email.trim().lowercase())
                cooldown = 30
            } catch (e: ApiException) {
                error = when (e.failure) {
                    ApiFailure.NOT_CONFIGURED -> "Password reset email is not set up yet. Please contact the ComplyVision Support Team."
                    else -> e.userMessage()
                }
            } finally {
                refreshCaptcha()
                submitAttempted = false
                touched = emptySet()
                submitting = false
            }
        }
    }

    Column {
        Staggered(0) {
            FormCard {
                AuthField(
                    value = orgId, onChange = { orgId = it.uppercase().filter { c -> !c.isWhitespace() }.take(20); error = null },
                    label = "Organization ID", placeholder = "Enter your organization ID", error = idErr, enabled = !submitting,
                    leading = { GlyphIcon(Glyph.ID_CARD, CBlue, Modifier.size(26.dp)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { emailF.requestFocus() }), onFocusLost = { touched = touched + "id" },
                    modifier = Modifier.focusRequester(idF)
                )
                AuthField(
                    value = email, onChange = { email = it.filter { c -> !c.isWhitespace() }.take(125); error = null },
                    label = "Authorized Person Email", placeholder = "Enter registered email address", error = emailErr, enabled = !submitting,
                    leading = { AIconView(AIcon.MAIL, CBlue, Modifier.size(24.dp)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { capF.requestFocus() }), onFocusLost = { touched = touched + "email" },
                    modifier = Modifier.focusRequester(emailF)
                )
                CaptchaRow(captcha, captchaInput, capErr, !submitting, capF, { captchaInput = it; error = null }, ::refreshCaptcha, ::submit, { touched = touched + "cap" })

                AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { ErrorBanner(error.orEmpty()) }
                AnimatedVisibility(sentTo != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE6F6EA))
                            .border(1.dp, Color(0xFFBFE3C8), RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlyphIcon(Glyph.CHECK, CGreen, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(sentTo.orEmpty() + " Check your inbox and spam folder; the link works once for 30 minutes.", fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFF17632A))
                    }
                }

                GradientButton("Send Reset Link", "Sending…", submitting, cooldown, ::submit, glyph = Glyph.LOGIN, cooldownLabel = "Resend in")

                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFE9F1FC)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.SHIELD_CHECK, CBlue, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("A one-time password reset link is emailed to your registered address by the ComplyVision Support Team.", fontSize = 12.5.sp, color = CMuted, lineHeight = 16.sp)
                }
                Text(
                    "Back to login", color = CBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).clickable(enabled = !submitting, role = Role.Button) { onBackToLogin() }.padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
