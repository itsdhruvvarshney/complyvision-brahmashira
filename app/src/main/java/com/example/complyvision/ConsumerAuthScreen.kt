package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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

private val CBlue = Color(0xFF0A63F0)
private val CNavy = Color(0xFF071D36)
private val CMuted = Color(0xFF566A85)
private val CBorder = Color(0xFFDCE7F5)
private val CHint = Color(0xFF7487A6)
private val CRed = Color(0xFFD93A3A)
private val CGreen = Color(0xFF2E9B3C)

private enum class AuthMode { LOGIN, REGISTER }

/**
 * Consumer Portal entry: one screen that morphs between Login (email + password) and
 * Create Account (full name, email, password, captcha) so switching never feels like a page load.
 */
@Composable
fun ConsumerAuthScreen(onBack: () -> Unit, onAuthenticated: (ConsumerSession) -> Unit) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var modeName by rememberSaveable { mutableStateOf(AuthMode.LOGIN.name) }
    val mode = AuthMode.valueOf(modeName)

    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf(LoginValidator.newCaptcha()) }
    var captchaInput by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    // A field shows its error after it has been left once, or after the first submit
    var touched by rememberSaveable { mutableStateOf(setOf<String>()) }
    var submitAttempted by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var cooldownSeconds by rememberSaveable { mutableIntStateOf(0) }

    val nameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val captchaFocus = remember { FocusRequester() }

    val registering = mode == AuthMode.REGISTER
    fun show(key: String) = submitAttempted || key in touched
    val nameError = if (registering && show("name")) ConsumerValidator.nameError(name) else null
    val emailError = if (show("email")) ConsumerValidator.emailError(email) else null
    val passwordError = if (show("password")) ConsumerValidator.passwordError(password, registering) else null
    val captchaError = if (registering && show("captcha")) LoginValidator.captchaError(captchaInput, captcha) else null

    LaunchedEffect(cooldownSeconds > 0) {
        while (cooldownSeconds > 0) {
            delay(1000)
            cooldownSeconds -= 1
        }
    }

    BackHandler(enabled = submitting) { }

    fun refreshCaptcha() {
        captcha = LoginValidator.newCaptcha()
        captchaInput = ""
        touched = touched - "captcha"
    }

    fun clearErrors() {
        submitAttempted = false
        touched = emptySet()
        formError = null
    }

    fun switchTo(target: AuthMode) {
        if (submitting || target == mode) return
        clearErrors()
        password = ""
        showPassword = false
        refreshCaptcha()
        modeName = target.name
    }

    fun submit() {
        if (submitting) return
        submitAttempted = true
        formError = null

        val firstInvalid = when {
            registering && ConsumerValidator.nameError(name) != null -> nameFocus
            ConsumerValidator.emailError(email) != null -> emailFocus
            ConsumerValidator.passwordError(password, registering) != null -> passwordFocus
            registering && LoginValidator.captchaError(captchaInput, captcha) != null -> captchaFocus
            else -> null
        }
        if (firstInvalid != null) {
            // A wrong captcha must never be re-usable
            if (firstInvalid === captchaFocus && captchaInput.length >= LoginValidator.CAPTCHA_LENGTH) refreshCaptcha()
            firstInvalid.requestFocus()
            return
        }
        if (cooldownSeconds > 0) return

        focusManager.clearFocus()
        keyboard?.hide()
        submitting = true

        scope.launch {
            try {
                val result = if (registering) {
                    ConsumerRepository.register(LoginValidator.normalizeName(name), email.trim().lowercase(), password)
                } else {
                    ConsumerRepository.login(email.trim().lowercase(), password)
                }
                when (result) {
                    is ConsumerAuthResult.Success -> {
                        onAuthenticated(result.session)
                        return@launch
                    }
                    is ConsumerAuthResult.InvalidCredentials -> {
                        val left = result.attemptsLeft
                        formError = buildString {
                            append("Email or password is incorrect.")
                            if (left != null && left in 1..3) append(" $left attempt${if (left == 1) "" else "s"} left before the account is locked.")
                        }
                        password = ""
                    }
                    is ConsumerAuthResult.Locked -> {
                        val minutes = (result.secondsLeft + 59) / 60
                        formError = "Too many failed attempts. This account is locked for about $minutes minute${if (minutes == 1) "" else "s"}."
                        password = ""
                        cooldownSeconds = minOf(result.secondsLeft, 60)
                    }
                    ConsumerAuthResult.Inactive -> formError = "This account has been deactivated. Please contact support."
                    ConsumerAuthResult.EmailTaken -> {
                        formError = "An account with this email already exists. Try logging in instead."
                        refreshCaptcha()
                    }
                    is ConsumerAuthResult.Rejected -> {
                        formError = result.message
                        refreshCaptcha()
                    }
                }
                submitAttempted = false
                touched = emptySet()
            } catch (e: ApiException) {
                formError = e.userMessage()
                if (registering) refreshCaptcha()
            } finally {
                submitting = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(portalPageBrush())) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
        ) {
            ConsumerHero(registering = registering, onBack = onBack, backEnabled = !submitting)

            Staggered(0) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                        .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0)),
                    shape = RoundedCornerShape(30.dp),
                    color = Color.White.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthModeSwitch(mode, enabled = !submitting, onSelect = ::switchTo)

                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = {
                                val dir = if (targetState == AuthMode.REGISTER) 1 else -1
                                (fadeIn(tween(260, 80)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 6 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -dir * it / 6 })
                            },
                            label = "authForm"
                        ) { target ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (target == AuthMode.REGISTER) {
                                    AuthField(
                                        value = name, onChange = { if (it.length <= ConsumerValidator.MAX_NAME + 10) name = it; formError = null },
                                        label = "Full Name", placeholder = "Enter your full name", error = nameError, enabled = !submitting,
                                        leading = { GlyphIcon(Glyph.PERSON, CBlue, Modifier.size(24.dp)) },
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                                        keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
                                        onFocusLost = { touched = touched + "name" },
                                        modifier = Modifier.focusRequester(nameFocus)
                                    )
                                }
                                AuthField(
                                    value = email, onChange = { email = it.filter { c -> !c.isWhitespace() }.take(ConsumerValidator.MAX_EMAIL + 5); formError = null },
                                    label = "Email Address", placeholder = "Enter your email address", error = emailError, enabled = !submitting,
                                    leading = { AIconView(AIcon.MAIL, CBlue, Modifier.size(24.dp)) },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                                    onFocusLost = { touched = touched + "email" },
                                    modifier = Modifier.focusRequester(emailFocus)
                                )
                                AuthField(
                                    value = password, onChange = { if (it.length <= ConsumerValidator.MAX_PASSWORD) password = it; formError = null },
                                    label = "Password", placeholder = "Enter your password", error = passwordError, enabled = !submitting,
                                    leading = { GlyphIcon(Glyph.LOCK, CBlue, Modifier.size(24.dp)) },
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password,
                                        imeAction = if (target == AuthMode.REGISTER) ImeAction.Next else ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onNext = { captchaFocus.requestFocus() }, onDone = { submit() }),
                                    onFocusLost = { touched = touched + "password" },
                                    trailing = {
                                        Box(
                                            Modifier.size(44.dp).clip(CircleShape)
                                                .semantics { contentDescription = if (showPassword) "Hide password" else "Show password" }
                                                .clickable(enabled = !submitting, role = Role.Button) { showPassword = !showPassword },
                                            contentAlignment = Alignment.Center
                                        ) { GlyphIcon(if (showPassword) Glyph.EYE_OFF else Glyph.EYE, CBlue, Modifier.size(24.dp)) }
                                    },
                                    modifier = Modifier.focusRequester(passwordFocus)
                                )
                                if (target == AuthMode.REGISTER) {
                                    PasswordMeter(password)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CaptchaImage(captcha, Modifier.weight(0.9f))
                                        Box(
                                            Modifier.size(54.dp).shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0))
                                                .clip(RoundedCornerShape(16.dp)).background(Color(0xFFEAF2FF)).border(1.dp, CBorder, RoundedCornerShape(16.dp))
                                                .semantics { contentDescription = "Refresh captcha" }
                                                .clickable(enabled = !submitting, role = Role.Button) { refreshCaptcha() },
                                            contentAlignment = Alignment.Center
                                        ) { GlyphIcon(Glyph.REFRESH, CBlue, Modifier.size(24.dp)) }
                                        AuthField(
                                            value = captchaInput,
                                            onChange = {
                                                val cleaned = it.filter { c -> c.isLetterOrDigit() }.uppercase()
                                                if (cleaned.length <= LoginValidator.CAPTCHA_LENGTH) captchaInput = cleaned
                                                formError = null
                                            },
                                            label = null, placeholder = "Enter Captcha", error = captchaError, enabled = !submitting,
                                            leading = { GlyphIcon(Glyph.SHIELD_CHECK, CBlue, Modifier.size(22.dp)) },
                                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { submit() }),
                                            onFocusLost = { touched = touched + "captcha" },
                                            compact = true,
                                            modifier = Modifier.weight(1.25f).focusRequester(captchaFocus)
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(formError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            ErrorBanner(formError.orEmpty())
                        }

                        Spacer(Modifier.height(2.dp))
                        GradientButton(
                            label = if (registering) "Create Account" else "Login",
                            loadingLabel = if (registering) "Creating account…" else "Signing in…",
                            loading = submitting, cooldownSeconds = cooldownSeconds,
                            onClick = ::submit
                        )

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(1.dp).background(CBorder))
                            Text("or", color = CBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp))
                            Box(Modifier.weight(1f).height(1.dp).background(CBorder))
                        }

                        OutlineButton(
                            label = if (registering) "I already have an account" else "Create New Account",
                            enabled = !submitting,
                            onClick = { switchTo(if (registering) AuthMode.LOGIN else AuthMode.REGISTER) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.SHIELD_CHECK, CGreen, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Your reports are private and visible only to you.",
                    fontSize = 12.sp, color = CMuted, lineHeight = 16.sp, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Fades and lifts its content in once, [index] steps after the screen opens */
@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + index * 90L)
        progress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 36.dp.toPx() }) { content() }
}

@Composable
private fun ConsumerHero(registering: Boolean, onBack: () -> Unit, backEnabled: Boolean) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        PortalTopBar(onBack = onBack, backEnabled = backEnabled)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(232.dp)) {
            Image(
                painter = painterResource(id = R.drawable.consumer_hero),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)
                    .graphicsLayer { alpha = 0.99f }
            )
            // Blend the artwork's left edge into the page
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0.38f to PortalPageTop, 0.62f to PortalPageTop.copy(alpha = 0f))
                )
            )
            Column(Modifier.align(Alignment.CenterStart).padding(start = 20.dp).fillMaxWidth(0.58f)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFDCEBFF)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.PERSON, CBlue, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Consumer Portal", color = CBlue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                AnimatedContent(registering, transitionSpec = { fadeIn(tween(300, 60)) togetherWith fadeOut(tween(160)) }, label = "heroTitle") { reg ->
                    Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = CNavy)) { append("Consumer\n") }
                                withStyle(SpanStyle(color = CBlue)) { append(if (reg) "Sign Up" else "Login") }
                            },
                            fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 37.sp, letterSpacing = (-0.8).sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (reg) "Create your free account to verify products and keep every report in one place."
                            else "Verify products, check compliance and stay informed as a consumer.",
                            fontSize = 13.5.sp, lineHeight = 18.sp, color = CMuted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun AuthModeSwitch(mode: AuthMode, enabled: Boolean, onSelect: (AuthMode) -> Unit) {
    val offset by animateFloatAsState(if (mode == AuthMode.LOGIN) 0f else 1f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "switch")
    BoxWithConstraints(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFFE6EFFB)).padding(4.dp)) {
        val half = maxWidth / 2
        Box(
            Modifier.offset(x = half * offset).width(half).fillMaxHeight()
                .shadow(4.dp, RoundedCornerShape(19.dp), ambientColor = Color(0x331D5FE0), spotColor = Color(0x331D5FE0))
                .clip(RoundedCornerShape(19.dp)).background(Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            AuthMode.entries.forEach { m ->
                val selected = m == mode
                val color by animateColorAsState(if (selected) CBlue else CMuted, label = "tab")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                        .clickable(enabled = enabled, role = Role.Tab) { onSelect(m) },
                    contentAlignment = Alignment.Center
                ) { Text(if (m == AuthMode.LOGIN) "Login" else "Register", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String?,
    placeholder: String,
    error: String?,
    enabled: Boolean,
    leading: @Composable () -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(
        when { error != null -> CRed; focused -> Color(0xFF2F7BFF); else -> CBorder }, label = "fieldBorder"
    )

    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = CNavy, fontSize = if (compact) 15.sp else 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = if (compact) 1.sp else 0.sp),
            cursorBrush = SolidColor(Color(0xFF2F7BFF)),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            interactionSource = remember { MutableInteractionSource() },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) hadFocus = true else if (hadFocus) onFocusLost()
            }.semantics { contentDescription = label ?: placeholder },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().height(if (label != null) 66.dp else 54.dp)
                        .shadow(if (focused) 5.dp else 2.dp, shape, ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0))
                        .clip(shape).background(if (enabled) Color.White.copy(alpha = 0.95f) else Color(0xFFF1F5FA))
                        .border(if (focused || error != null) 1.5.dp else 1.dp, borderColor, shape)
                        .padding(start = if (compact) 10.dp else 14.dp, end = if (trailing != null) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(if (compact) 28.dp else 34.dp), contentAlignment = Alignment.Center) { leading() }
                    if (!compact) Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(30.dp).background(CBorder)) else Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        if (label != null) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = CNavy, fontWeight = FontWeight.Bold)) { append(label) }
                                    withStyle(SpanStyle(color = CRed, fontWeight = FontWeight.Bold)) { append(" *") }
                                },
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text(placeholder, fontSize = if (compact) 14.sp else 15.sp, color = CHint, maxLines = 1)
                            inner()
                        }
                    }
                    trailing?.invoke()
                }
            }
        )
        AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(error.orEmpty(), fontSize = 12.sp, lineHeight = 15.sp, color = CRed, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        }
    }
}

@Composable
private fun PasswordMeter(password: String) {
    val strength = ConsumerValidator.passwordStrength(password)
    val (label, color) = when (strength) {
        0 -> "Use 6+ characters with letters and numbers" to CHint
        1 -> "Weak" to CRed
        2 -> "Fair" to Color(0xFFE08A00)
        3 -> "Good" to Color(0xFF2E9B3C)
        else -> "Strong" to Color(0xFF15803D)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                val c by animateColorAsState(if (i < strength) color else Color(0xFFE1EAF6), label = "meter")
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(c))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.5.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFDECEC))
            .border(1.dp, Color(0xFFF4C1C1), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Error: $message" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.WARNING, CRed, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFF9B2226))
    }
}

@Composable
private fun GradientButton(label: String, loadingLabel: String, loading: Boolean, cooldownSeconds: Int, onClick: () -> Unit) {
    val blocked = cooldownSeconds > 0
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier.fillMaxWidth().height(58.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (loading || blocked) 2.dp else 10.dp, shape, ambientColor = Color(0x661D5FE0), spotColor = Color(0x661D5FE0))
            .clip(shape)
            .background(Brush.horizontalGradient(if (blocked) listOf(Color(0xFF9AA9C2), Color(0xFF8493AE)) else listOf(Color(0xFF2F9BFF), Color(0xFF1648D8))))
            .clickable(interactionSource = source, indication = null, enabled = !loading, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                loading -> {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(loadingLabel, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                blocked -> Text("Try again in ${cooldownSeconds}s", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                else -> {
                    GlyphIcon(Glyph.LOGIN, Color.White, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier.fillMaxWidth().height(54.dp).graphicsLayer { scaleX = scale; scaleY = scale }.alpha(if (enabled) 1f else 0.5f)
            .clip(shape).background(Color.White.copy(alpha = 0.7f)).border(1.5.dp, CBlue, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.USER, CBlue, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = CBlue, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
