package com.example.complyvision

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random

private val FieldBorder = Color(0xFFDCE7F5)
private val FieldPlaceholder = Color(0xFF6F82A3)
private val ErrorRed = Color(0xFFD93A3A)
private const val MAX_CLIENT_ATTEMPTS = 5
private const val CLIENT_COOLDOWN_SECONDS = 30

@Composable
fun OfficerLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: (OfficerSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var name by rememberSaveable { mutableStateOf("") }
    var officerId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaInput by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf(LoginValidator.newCaptcha()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    // A field shows its error after it has been left once, or after the first Login tap
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    var idTouched by rememberSaveable { mutableStateOf(false) }
    var passwordTouched by rememberSaveable { mutableStateOf(false) }
    var captchaTouched by rememberSaveable { mutableStateOf(false) }
    var submitAttempted by rememberSaveable { mutableStateOf(false) }

    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var failedAttempts by rememberSaveable { mutableIntStateOf(0) }
    var cooldownSeconds by rememberSaveable { mutableIntStateOf(0) }

    val nameFocus = remember { FocusRequester() }
    val idFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val captchaFocus = remember { FocusRequester() }

    val nameError = if (nameTouched || submitAttempted) LoginValidator.nameError(name) else null
    val idError = if (idTouched || submitAttempted) LoginValidator.officerIdError(officerId) else null
    val passwordError = if (passwordTouched || submitAttempted) LoginValidator.passwordError(password) else null
    val captchaError = if (captchaTouched || submitAttempted) LoginValidator.captchaError(captchaInput, captcha) else null

    // Counts a cooldown down once per second
    LaunchedEffect(cooldownSeconds > 0) {
        while (cooldownSeconds > 0) {
            delay(1000)
            cooldownSeconds -= 1
        }
    }

    // Ignore the system back gesture while a request is in flight
    BackHandler(enabled = submitting) { }

    fun refreshCaptcha() {
        captcha = LoginValidator.newCaptcha()
        captchaInput = ""
        captchaTouched = false
    }

    // After the server rejects a login only the banner should speak; the cleared fields are not "errors"
    fun resetFieldErrors() {
        submitAttempted = false
        passwordTouched = false
        captchaTouched = false
    }

    fun submit() {
        if (submitting) return
        submitAttempted = true
        formError = null

        val firstInvalid = when {
            LoginValidator.nameError(name) != null -> nameFocus
            LoginValidator.officerIdError(officerId) != null -> idFocus
            LoginValidator.passwordError(password) != null -> passwordFocus
            LoginValidator.captchaError(captchaInput, captcha) != null -> captchaFocus
            else -> null
        }
        if (firstInvalid != null) {
            // A wrong captcha must never be re-usable: issue a fresh one
            if (firstInvalid === captchaFocus && captchaInput.length >= LoginValidator.CAPTCHA_LENGTH) {
                refreshCaptcha()
            }
            firstInvalid.requestFocus()
            return
        }
        if (cooldownSeconds > 0) return

        focusManager.clearFocus()
        keyboard?.hide()
        submitting = true

        scope.launch {
            try {
                when (
                    val result = OfficerRepository.login(
                        officerId = officerId.trim().uppercase(),
                        password = password,
                        officerName = LoginValidator.normalizeName(name)
                    )
                ) {
                    is LoginResult.Success -> {
                        failedAttempts = 0
                        onLoggedIn(result.session)
                        return@launch
                    }

                    is LoginResult.InvalidCredentials -> {
                        failedAttempts += 1
                        val left = result.attemptsLeft
                        formError = buildString {
                            append("Officer ID, password or name is incorrect.")
                            if (left != null && left in 1..3) {
                                append(" $left attempt${if (left == 1) "" else "s"} left before the account is locked.")
                            }
                        }
                        password = ""
                        refreshCaptcha()
                        resetFieldErrors()
                        if (failedAttempts >= MAX_CLIENT_ATTEMPTS) {
                            cooldownSeconds = CLIENT_COOLDOWN_SECONDS
                            failedAttempts = 0
                        }
                    }

                    is LoginResult.Locked -> {
                        val minutes = (result.secondsLeft + 59) / 60
                        formError = "Too many failed attempts. This account is locked for about " +
                            "$minutes minute${if (minutes == 1) "" else "s"}."
                        password = ""
                        refreshCaptcha()
                        resetFieldErrors()
                        cooldownSeconds = minOf(result.secondsLeft, 60)
                    }

                    LoginResult.Inactive -> {
                        formError = "This account has been deactivated. Please contact your administrator."
                        refreshCaptcha()
                        resetFieldErrors()
                    }
                }
            } catch (e: ApiException) {
                formError = e.userMessage()
                refreshCaptcha()
                resetFieldErrors()
            } finally {
                submitting = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(portalPageBrush())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
        ) {
            PortalHero(onBack = onBack, backEnabled = !submitting) {
                Image(
                    painter = painterResource(id = R.drawable.lm_icon_tile),
                    contentDescription = "Legal Metrology",
                    modifier = Modifier
                        .size(62.dp)
                        .shadow(
                            6.dp, RoundedCornerShape(16.dp),
                            ambientColor = Color(0x331D4ED8), spotColor = Color(0x331D4ED8)
                        )
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF050B18))) { append("Legal Metrology\n") }
                        withStyle(SpanStyle(color = PortalBlue)) { append("Officer Login") }
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 29.sp,
                    letterSpacing = (-0.6).sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Secure access for authorized Legal Metrology officers.",
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = PortalMuted,
                    modifier = Modifier.widthIn(max = 200.dp)
                )
            }

            // Form card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .shadow(
                        8.dp, RoundedCornerShape(28.dp),
                        ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LoginField(
                        value = name,
                        onValueChange = {
                            if (it.length <= LoginValidator.MAX_NAME + 10) name = it
                            formError = null
                        },
                        placeholder = "Name of Officer",
                        glyph = Glyph.PERSON,
                        error = nameError,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { idFocus.requestFocus() }),
                        onFocusLost = { nameTouched = true },
                        modifier = Modifier.focusRequester(nameFocus)
                    )

                    LoginField(
                        value = officerId,
                        onValueChange = {
                            // IDs are upper-case; spaces are never valid so they are dropped as typed
                            val cleaned = it.uppercase().filter { c -> !c.isWhitespace() }
                            if (cleaned.length <= LoginValidator.MAX_ID) officerId = cleaned
                            formError = null
                        },
                        placeholder = "Officer ID",
                        glyph = Glyph.ID_CARD,
                        error = idError,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        onFocusLost = { idTouched = true },
                        modifier = Modifier.focusRequester(idFocus)
                    )

                    LoginField(
                        value = password,
                        onValueChange = {
                            if (it.length <= LoginValidator.MAX_PASSWORD) password = it
                            formError = null
                        },
                        placeholder = "Password",
                        glyph = Glyph.LOCK,
                        error = passwordError,
                        enabled = !submitting,
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { captchaFocus.requestFocus() }),
                        onFocusLost = { passwordTouched = true },
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .semantics {
                                        contentDescription = if (showPassword) "Hide password" else "Show password"
                                    }
                                    .clickable(enabled = !submitting, role = Role.Button) {
                                        showPassword = !showPassword
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                GlyphIcon(
                                    glyph = if (showPassword) Glyph.EYE_OFF else Glyph.EYE,
                                    tint = Color(0xFF3B5C96),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        },
                        modifier = Modifier.focusRequester(passwordFocus)
                    )

                    // Captcha row: image + refresh + input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CaptchaImage(
                            text = captcha,
                            modifier = Modifier.weight(0.9f)
                        )

                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0))
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFFEAF2FF))
                                .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
                                .semantics { contentDescription = "Refresh captcha" }
                                .clickable(enabled = !submitting, role = Role.Button) { refreshCaptcha() },
                            contentAlignment = Alignment.Center
                        ) {
                            GlyphIcon(Glyph.REFRESH, Color(0xFF1D5FE0), Modifier.size(24.dp))
                        }

                        LoginField(
                            value = captchaInput,
                            onValueChange = {
                                val cleaned = it.filter { c -> c.isLetterOrDigit() }.uppercase()
                                if (cleaned.length <= LoginValidator.CAPTCHA_LENGTH) captchaInput = cleaned
                                formError = null
                            },
                            placeholder = "Enter Captcha",
                            glyph = Glyph.SHIELD_CHECK,
                            error = captchaError,
                            enabled = !submitting,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            onFocusLost = { captchaTouched = true },
                            compact = true,
                            modifier = Modifier
                                .weight(1.25f)
                                .focusRequester(captchaFocus)
                        )
                    }

                    AnimatedVisibility(
                        visible = formError != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        FormErrorBanner(message = formError.orEmpty())
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    LoginButton(
                        loading = submitting,
                        cooldownSeconds = cooldownSeconds,
                        onClick = { submit() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Trouble signing in? Contact your department administrator.",
                fontSize = 12.sp,
                color = PortalMuted,
                lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )

            // Decorative footer art that fades into the page
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.business_footer_bg),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomEnd,
                    modifier = Modifier.matchParentSize()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xFFF2F7FD),
                                0.5f to Color(0x00F2F7FD),
                                0.8f to Color(0x00F6F9FD),
                                1f to Color(0xFFF6F9FD)
                            )
                        )
                )
            }
        }
    }
}

/**
 * Rounded input with a leading glyph, optional trailing action and an animated inline error.
 * Errors, focus and disabled states are all reflected in the border so state is obvious at a glance.
 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    glyph: Glyph,
    error: String?,
    enabled: Boolean,
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
    val shape = RoundedCornerShape(16.dp)
    val borderColor by animateColorAsState(
        targetValue = when {
            error != null -> ErrorRed
            focused -> Color(0xFF2F7BFF)
            else -> FieldBorder
        },
        label = "fieldBorder"
    )

    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = PortalNavy,
                fontSize = if (compact) 15.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = if (compact) 1.sp else 0.sp
            ),
            cursorBrush = SolidColor(Color(0xFF2F7BFF)),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = remember { MutableInteractionSource() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus) onFocusLost()
                }
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .shadow(
                            if (focused) 4.dp else 2.dp, shape,
                            ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0)
                        )
                        .clip(shape)
                        .background(if (enabled) Color.White.copy(alpha = 0.94f) else Color(0xFFF1F5FA))
                        .border(if (focused || error != null) 1.5.dp else 1.dp, borderColor, shape)
                        .padding(start = if (compact) 10.dp else 14.dp, end = if (trailing != null) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(
                        glyph = glyph,
                        tint = if (error != null) ErrorRed else Color(0xFF3B5C96),
                        modifier = Modifier.size(if (compact) 22.dp else 24.dp)
                    )
                    Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = if (compact) 14.sp else 16.sp,
                                color = FieldPlaceholder,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            }
        )

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = error.orEmpty(),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = ErrorRed
                )
            }
        }
    }
}

/** Distorted code on a noisy tile; the characters come from [text] and the noise is seeded by it */
@Composable
internal fun CaptchaImage(text: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        letterSpacing = 1.sp,
        color = Color(0xFF14267A)
    )

    Canvas(
        modifier = modifier
            .height(54.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1A1E5FB0), spotColor = Color(0x1A1E5FB0))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFE2EEFF), Color(0xFFF3F8FF), Color(0xFFDCE9FD))))
            .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Captcha image. Characters: ${text.toList().joinToString(" ")}" }
    ) {
        val rnd = Random(text.hashCode().toLong())

        // Background noise: soft dots and wavy strikes
        repeat(26) {
            drawCircle(
                color = Color(0xFF2F5FC4).copy(alpha = 0.25f),
                radius = 1.2.dp.toPx(),
                center = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)
            )
        }
        repeat(3) {
            drawLine(
                color = Color(0xFF2F5FC4).copy(alpha = 0.35f),
                start = Offset(rnd.nextFloat() * size.width * 0.3f, rnd.nextFloat() * size.height),
                end = Offset(size.width * (0.7f + rnd.nextFloat() * 0.3f), rnd.nextFloat() * size.height),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Characters with slight rotation and vertical jitter
        val slot = size.width / text.length
        text.forEachIndexed { i, ch ->
            val layout = measurer.measure(ch.toString(), style)
            val x = slot * i + (slot - layout.size.width) / 2f
            val y = (size.height - layout.size.height) / 2f + (rnd.nextFloat() - 0.5f) * 8.dp.toPx()
            rotate(degrees = (rnd.nextFloat() - 0.5f) * 24f, pivot = Offset(x + layout.size.width / 2f, size.height / 2f)) {
                drawText(layout, topLeft = Offset(x, y))
            }
        }

        // A strike-through curve over the code
        drawLine(
            color = Color(0xFF14267A).copy(alpha = 0.3f),
            start = Offset(0f, size.height * (0.3f + rnd.nextFloat() * 0.4f)),
            end = Offset(size.width, size.height * (0.3f + rnd.nextFloat() * 0.4f)),
            strokeWidth = 1.2.dp.toPx()
        )
    }
}

@Composable
private fun FormErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFDECEC))
            .border(1.dp, Color(0xFFF4C1C1), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Error: $message" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(Glyph.WARNING, ErrorRed, Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = message, fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFF9B2226))
    }
}

@Composable
private fun LoginButton(loading: Boolean, cooldownSeconds: Int, onClick: () -> Unit) {
    val blocked = cooldownSeconds > 0
    val shape = RoundedCornerShape(28.dp)
    val active = !loading && !blocked

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                if (active) 10.dp else 2.dp, shape,
                ambientColor = Color(0x661D5FE0), spotColor = Color(0x661D5FE0)
            )
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    if (blocked) listOf(Color(0xFF9AA9C2), Color(0xFF8493AE))
                    else listOf(Color(0xFF2F86FF), Color(0xFF1648D8))
                )
            )
            .clickable(enabled = !loading, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Signing in…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                blocked -> Text(
                    text = "Try again in ${cooldownSeconds}s",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )

                else -> {
                    GlyphIcon(Glyph.LOGIN, Color.White, Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Login", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
