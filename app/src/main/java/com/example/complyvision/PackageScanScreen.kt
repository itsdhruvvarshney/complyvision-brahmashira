package com.example.complyvision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private sealed interface ScanStage {
    data object Capture : ScanStage
    data object Checking : ScanStage
    data class Ready(val image: ValidatedImage, val source: String) : ScanStage
    data class Analyzing(val image: ValidatedImage, val source: String) : ScanStage
    data class Done(val image: ValidatedImage, val source: String, val json: JSONObject, val report: PackageReport) : ScanStage
    data class Failed(val message: String, val image: ValidatedImage?, val source: String, val retryable: Boolean) : ScanStage
}

private sealed interface SaveState {
    data object Saving : SaveState
    data class Saved(val scan: SavedScan) : SaveState
    data class Error(val message: String) : SaveState
}

/** Label guide frame, as fractions of the viewfinder (the photo is cropped to the same area) */
private const val GUIDE_W = 0.84f
private const val GUIDE_H = 0.46f
private const val GUIDE_TOP = 0.2f
private const val GUIDE_MARGIN = 0.04f

/**
 * Maps the on-screen guide frame onto the captured photo. The preview is FILL_CENTER, so it shows only
 * the central part of the sensor image; the visible region is worked out from the view and photo sizes.
 */
private fun guideCrop(view: IntSize): (Int, Int) -> android.graphics.Rect = { w, h ->
    val scale = maxOf(view.width.toFloat() / w, view.height.toFloat() / h)
    val visW = view.width / scale
    val visH = view.height / scale
    val offX = (w - visW) / 2f
    val offY = (h - visH) / 2f
    val gw = GUIDE_W + 2 * GUIDE_MARGIN
    val gh = GUIDE_H + 2 * GUIDE_MARGIN
    val left = offX + visW * (1f - gw) / 2f
    val top = offY + visH * (GUIDE_TOP - GUIDE_MARGIN)
    android.graphics.Rect(left.toInt(), top.toInt(), (left + visW * gw).toInt(), (top + visH * gh).toInt())
}

/** The analyzer stages, in the order the FastAPI service runs them */
private val AnalyzerStages = listOf(
    "MIME & integrity validation",
    "Image quality assessment",
    "Perspective & tilt correction",
    "ArUco scale calibration",
    "Glare, exposure & contrast correction",
    "PaddleOCR text recognition",
    "Conditional OCR verification",
    "Field extraction (MRP, net qty, dates…)",
    "Numeral height & local contrast",
    "Gemini enrichment",
    "Product categorisation",
    "Category rule engine (LM, FSS, BIS…)",
    "Evidence crops & canonical report"
)

private val PackageChoices = listOf(null to "Auto", "retail" to "Retail", "wholesale" to "Wholesale", "institutional" to "Institutional")

@Composable
fun PackageScanScreen(
    backend: ScanBackend,
    exporter: ReportExporter,
    case: ScanTarget,
    onClose: () -> Unit,
    onOpenReport: (String) -> Unit,
    /** Opens the photo picker straight away (still falls back to the camera if nothing is picked) */
    startWithGallery: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ScanStage>(if (startWithGallery) ScanStage.Checking else ScanStage.Capture) }
    var save by remember { mutableStateOf<SaveState?>(null) }
    var uploaded by remember { mutableFloatStateOf(0f) }
    var sessionCount by remember { mutableIntStateOf(0) }

    // Rule-engine settings: null category = let the engine categorise the product
    var categoryChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var packageChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var panelW by rememberSaveable { mutableStateOf("") }
    var panelH by rememberSaveable { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    var awaitingConfirm by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { backend.loadCategories() }
    val panelArea = panelW.toDoubleOrNull()?.let { w -> panelH.toDoubleOrNull()?.let { h -> w * h } }?.takeIf { it in 1.0..20000.0 }

    /** MIME-validates the original, then crops / resizes it on the phone before upload */
    fun accept(check: ImageCheck, source: String, crop: ((Int, Int) -> android.graphics.Rect)? = null) {
        when (check) {
            is ImageCheck.Valid -> {
                stage = ScanStage.Checking
                scope.launch {
                    val ready = withContext(Dispatchers.Default) { ImageValidator.optimize(check.image, crop) }
                    stage = ScanStage.Ready(ready, source)
                }
            }
            is ImageCheck.Invalid -> stage = ScanStage.Failed(check.message, null, source, retryable = false)
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            stage = ScanStage.Checking
            scope.launch { accept(ImageValidator.validate(context, uri, "package"), "Gallery") }
        } else if (stage == ScanStage.Checking) {
            stage = ScanStage.Capture // the picker was dismissed without a photo
        }
    }
    fun pickFromGallery() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    LaunchedEffect(Unit) { if (startWithGallery) pickFromGallery() }

    fun persist(json: JSONObject, source: String) {
        save = SaveState.Saving
        scope.launch {
            save = when (val r = backend.saveScan(json, source)) {
                is SaveScanResult.Saved -> {
                    sessionCount++
                    SaveState.Saved(r.scan)
                }
                is SaveScanResult.Rejected -> SaveState.Error(r.message)
            }
        }
    }

    fun analyse(image: ValidatedImage, source: String) {
        stage = ScanStage.Analyzing(image, source)
        uploaded = 0f
        save = null
        awaitingConfirm = false
        scope.launch {
            try {
                val json = AnalyzerClient.analyze(
                    image,
                    mapOf(
                        "case_code" to case.inspection?.code,
                        "product_name" to case.product,
                        "brand" to case.brand,
                        "category" to case.category,
                        "declared_mrp" to case.mrp?.let { String.format(Locale.US, "%.2f", it) },
                        "source" to source,
                        "category_id" to categoryChoice,
                        "package_type" to packageChoice,
                        "pdp_area_cm2" to panelArea?.let { String.format(Locale.US, "%.1f", it) }
                    )
                ) { uploaded = it }
                val report = withContext(Dispatchers.Default) { PackageReport.parse(json) }
                stage = ScanStage.Done(image, source, json, report)
                // A low-confidence category would apply the wrong standards: hold the save until the inspector confirms
                if (report.classification?.needsConfirmation == true) awaitingConfirm = true else persist(json, source)
            } catch (e: AnalyzerException) {
                stage = ScanStage.Failed(e.message ?: "Analysis failed.", image, source,
                    retryable = e.failure != AnalyzerFailure.REJECTED && e.failure != AnalyzerFailure.NOT_CONFIGURED)
            }
        }
    }

    BackHandler {
        when (val s = stage) {
            is ScanStage.Analyzing, ScanStage.Checking -> Unit // let the request finish
            is ScanStage.Ready -> stage = ScanStage.Capture
            is ScanStage.Failed -> stage = if (s.image != null) ScanStage.Ready(s.image, s.source) else ScanStage.Capture
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07142A))) {
        when (val s = stage) {
            ScanStage.Capture -> CaptureView(
                case = case,
                sessionCount = sessionCount,
                onCaptured = { bytes, view ->
                    stage = ScanStage.Checking
                    scope.launch {
                        val check = withContext(Dispatchers.Default) { ImageValidator.checkBytes(bytes, "capture.jpg", "image/jpeg") }
                        accept(check, "Camera", if (view.width > 0 && view.height > 0) guideCrop(view) else null)
                    }
                },
                onCaptureError = { stage = ScanStage.Failed(it, null, "Camera", retryable = false) },
                onGallery = ::pickFromGallery,
                onClose = onClose
            )

            ScanStage.Checking -> CenterMessage("Validating image…", "Checking the file type, size and integrity")

            is ScanStage.Ready -> ReadyView(
                image = s.image,
                source = s.source,
                category = backend.categories?.byId(categoryChoice),
                caseCategory = case.category,
                packageChoice = packageChoice,
                panelW = panelW,
                panelH = panelH,
                onPickCategory = { pickerOpen = true },
                onPackage = { packageChoice = it },
                onPanel = { w, h -> panelW = w; panelH = h },
                onRetake = { stage = ScanStage.Capture },
                onAnalyse = { analyse(s.image, s.source) }
            )

            is ScanStage.Analyzing -> AnalyzingView(s.image, uploaded)

            is ScanStage.Failed -> FailedView(
                message = s.message,
                retryable = s.retryable && s.image != null,
                onRetry = { s.image?.let { analyse(it, s.source) } },
                onRetake = { stage = ScanStage.Capture },
                onGallery = ::pickFromGallery,
                onClose = onClose
            )

            is ScanStage.Done -> ResultView(
                case = case,
                report = s.report,
                save = save,
                exporter = exporter,
                awaitingConfirm = awaitingConfirm,
                resolving = resolving,
                onConfirm = { awaitingConfirm = false; persist(s.json, s.source) },
                // Re-checking with another category is offered until the result is saved
                onChangeCategory = if (save == null || save is SaveState.Error) ({ pickerOpen = true }) else null,
                onResolve = (save as? SaveState.Saved)?.takeIf { backend.canResolve }?.let { saved ->
                    { decisions: List<RuleDecision> ->
                        resolving = true
                        scope.launch {
                            when (val r = backend.resolveScan(saved.scan.scanCode, decisions)) {
                                is ActionResult.Done -> {
                                    try {
                                        val (sum, fresh) = backend.reloadScan(saved.scan.scanCode)
                                        stage = s.copy(report = fresh)
                                        save = SaveState.Saved(SavedScan(sum.scanCode, sum.verdict, sum.passed, sum.failed, sum.review, sum.score))
                                    } catch (_: Exception) {
                                    }
                                    backend.showToast("Decisions saved - package re-scored")
                                }
                                is ActionResult.Rejected -> backend.showToast(r.message, isError = true)
                            }
                            resolving = false
                        }
                    }
                },
                onRetrySave = { persist(s.json, s.source) },
                onNext = { stage = ScanStage.Capture; save = null; awaitingConfirm = false },
                onOpen = { (save as? SaveState.Saved)?.let { onOpenReport(it.scan.scanCode) } },
                onClose = onClose
            )
        }

        if (pickerOpen) {
            CategoryPickerDialog(
                catalog = backend.categories,
                selectedId = categoryChoice,
                onPick = { picked ->
                    pickerOpen = false
                    categoryChoice = picked?.id
                    (stage as? ScanStage.Done)?.let { d -> analyse(d.image, d.source) }
                },
                onDismiss = { pickerOpen = false }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Capture: CameraX viewfinder with a label guide, torch and tap-to-focus
// ---------------------------------------------------------------------------

@Composable
private fun CaptureView(
    case: ScanTarget,
    sessionCount: Int,
    onCaptured: (ByteArray, IntSize) -> Unit,
    onCaptureError: (String) -> Unit,
    onGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(92)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var torch by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    var focusing by remember { mutableStateOf(false) }

    fun capture() {
        val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bytes = try { file.readBytes() } catch (_: Exception) { null }
                    file.delete()
                    shooting = false
                    camera?.cameraControl?.cancelFocusAndMetering()
                    if (bytes != null) onCaptured(bytes, viewSize) else onCaptureError("The photo could not be saved. Please try again.")
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    shooting = false
                    camera?.cameraControl?.cancelFocusAndMetering()
                    onCaptureError("The camera could not take the photo (${exception.imageCaptureError}). Please try again.")
                }
            }
        )
    }

    // Focus and expose on the label (centre of the guide frame) before the shutter fires
    fun shoot() {
        shooting = true
        val cam = camera
        val pv = previewView
        if (cam == null || pv == null || viewSize == IntSize.Zero) {
            capture()
            return
        }
        focusing = true
        var fired = false
        val fire = {
            if (!fired) {
                fired = true
                focusing = false
                capture()
            }
        }
        val point = pv.meteringPointFactory.createPoint(viewSize.width / 2f, viewSize.height * (GUIDE_TOP + GUIDE_H / 2f), 0.3f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action).addListener({ fire() }, ContextCompat.getMainExecutor(context))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fire() }, 1800)
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        if (granted) {
            CameraPreview(imageCapture, Modifier.fillMaxSize()) { cam, view -> camera = cam; previewView = view }
            GuideOverlay()
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AIconView(AIcon.BAN, Color.White, Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                Text("Camera access is needed to scan packages", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    if (asked) "Allow camera access in system settings, or upload a photo you already took." else "Waiting for permission…",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                SoftButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) }, height = 44.dp)
            }
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DarkRoundButton(AIcon.CLOSE, "Close scanner", onClose)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(case.product, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "${case.subtitle} • package ${case.savedScans + sessionCount + 1}" + if (sessionCount > 0) " • $sessionCount saved now" else "",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp
                )
            }
            if (granted) DarkRoundButton(AIcon.FLASH, if (torch) "Turn torch off" else "Turn torch on", {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }, active = torch)
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC07142A))))
                .padding(top = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (focusing) "Focusing on the label…"
                else "Fill the frame with the label - only the area inside it is analysed. Hold steady, avoid glare.\nKeep the 20 mm ArUco card inside the frame to measure numeral height.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    DarkRoundButton(AIcon.UPLOAD, "Upload from gallery", onGallery, size = 52)
                    Text("Upload", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Box(
                    Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (granted && !shooting) Color.White else Color.White.copy(alpha = 0.4f))
                        .semantics { contentDescription = "Capture package photo" }
                        .clickable(enabled = granted && !shooting, role = Role.Button) { shoot() },
                    contentAlignment = Alignment.Center
                ) {
                    if (shooting) CircularProgressIndicator(color = AC.Blue, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture, modifier: Modifier, onCamera: (Camera, PreviewView) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)?.let {
                    camera = it
                    onCamera(it, previewView)
                }
            } catch (_: Exception) {
                // No back camera / camera in use: the gallery upload remains available
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
        }
    )
}

@Composable
private fun GuideOverlay() {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "sweep")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * GUIDE_W
        val h = size.height * GUIDE_H
        val left = (size.width - w) / 2
        val top = size.height * GUIDE_TOP
        val dim = Color(0x88000000)
        drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, top))
        drawRect(dim, Offset(0f, top + h), androidx.compose.ui.geometry.Size(size.width, size.height - top - h))
        drawRect(dim, Offset(0f, top), androidx.compose.ui.geometry.Size(left, h))
        drawRect(dim, Offset(left + w, top), androidx.compose.ui.geometry.Size(left, h))
        val corner = 34.dp.toPx()
        val sw = 4.dp.toPx()
        val c = Color(0xFF5EA2FF)
        listOf(
            Offset(left, top) to Offset(1f, 1f), Offset(left + w, top) to Offset(-1f, 1f),
            Offset(left, top + h) to Offset(1f, -1f), Offset(left + w, top + h) to Offset(-1f, -1f)
        ).forEach { (p, d) ->
            drawLine(c, p, Offset(p.x + corner * d.x, p.y), sw, StrokeCap.Round)
            drawLine(c, p, Offset(p.x, p.y + corner * d.y), sw, StrokeCap.Round)
        }
        val y = top + h * sweep
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c, Color.Transparent), left, left + w), Offset(left + 8f, y), Offset(left + w - 8f, y), 2.dp.toPx())
    }
}

@Composable
private fun DarkRoundButton(icon: AIcon, description: String, onClick: () -> Unit, size: Int = 42, active: Boolean = false) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (active) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.16f))
            .semantics { contentDescription = description }.clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) { AIconView(icon, if (active) AC.Ink else Color.White, Modifier.size((size * 0.46f).dp)) }
}

// ---------------------------------------------------------------------------
// Validated preview
// ---------------------------------------------------------------------------

@Composable
private fun ReadyView(
    image: ValidatedImage,
    source: String,
    category: ProductCategory?,
    caseCategory: String,
    packageChoice: String?,
    panelW: String,
    panelH: String,
    onPickCategory: () -> Unit,
    onPackage: (String?) -> Unit,
    onPanel: (String, String) -> Unit,
    onRetake: () -> Unit,
    onAnalyse: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Text("Check the photo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("The label should be in focus and fully visible.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Image(
            bitmap = image.preview.asImageBitmap(), contentDescription = "Captured package",
            contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black)
        )
        Spacer(Modifier.height(12.dp))

        // Rule-engine settings: what the product is decides which standards are verified
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onPickCategory() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AIconView(AIcon.BOX, Color(0xFF5EA2FF), Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Product category", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        category?.let { "${it.name} • ${it.rules} rules" } ?: "Auto-detect" + if (caseCategory.isNotBlank()) " (case: $caseCategory)" else "",
                        color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text("Change", color = Color(0xFF5EA2FF), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PackageChoices.forEach { (id, label) ->
                    val on = packageChoice == id
                    Text(
                        label, color = if (on) AC.Ink else Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                            .clickable(role = Role.Button) { onPackage(id) }.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Label panel (cm)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                CmField(panelW, "W") { onPanel(it, panelH) }
                Text(" × ", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                CmField(panelH, "H") { onPanel(panelW, it) }
            }
            Text("Optional - the panel size sets the Rule 7 minimum font height; without it the ArUco photo scale estimates it.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 10.5.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AIconView(AIcon.SHIELD, AC.Green, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "MIME validated • sending ${image.mime} ${image.width}×${image.height}px • ${image.sizeLabel} • $source" +
                    if (image.note.isNotBlank()) "\nPrepared on the phone: ${image.note}" else "",
                color = Color.White, fontSize = 12.sp, lineHeight = 16.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Retake", onRetake, Modifier.weight(1f), fg = Color.White, bg = Color.White.copy(alpha = 0.14f), height = 50.dp)
            PrimaryButton("Analyse package", onAnalyse, Modifier.weight(1.5f), icon = AIcon.SEARCH, height = 50.dp)
        }
    }
}

@Composable
private fun CmField(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier.width(58.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// Analysis in progress
// ---------------------------------------------------------------------------

@Composable
private fun AnalyzingView(image: ValidatedImage, uploaded: Float) {
    var tick by remember { mutableIntStateOf(0) }
    val uploading = uploaded < 1f
    LaunchedEffect(uploading) {
        if (!uploading) {
            while (tick < AnalyzerStages.size - 1) {
                delay(if (tick in 4..5) 1600 else 750)
                tick++
            }
        }
    }
    val transition = rememberInfiniteTransition(label = "analyse")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "line")

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Analysing package", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            if (uploading) "Uploading securely to the ComplyVision analyzer… ${(uploaded * 100).toInt()}%"
            else "Running the computer-vision pipeline on the analyzer",
            color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            Image(image.preview.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * sweep
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x553B8BFF)), y - 60f, y), Offset(0f, y - 60f),
                    androidx.compose.ui.geometry.Size(size.width, 60f))
                drawLine(Color(0xFF5EA2FF), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressTrack(if (uploading) uploaded * 0.15f else 0.15f + 0.85f * (tick + 1) / AnalyzerStages.size, AC.Blue)
        Spacer(Modifier.height(14.dp))
        AnalyzerStages.forEachIndexed { i, label ->
            val state = when {
                uploading -> if (i == 0) 1 else 0
                i < tick -> 2
                i == tick -> 1
                else -> 0
            }
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        2 -> AIconView(AIcon.CHECK_CIRCLE, AC.Green, Modifier.size(18.dp))
                        1 -> CircularProgressIndicator(color = Color(0xFF5EA2FF), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        else -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(label, color = if (state == 0) Color.White.copy(alpha = 0.45f) else Color.White, fontSize = 13.5.sp,
                    fontWeight = if (state == 1) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Exact stage timings are shown in the report.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp)
    }
}

@Composable
private fun CenterMessage(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color(0xFF5EA2FF), strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}

@Composable
private fun FailedView(message: String, retryable: Boolean, onRetry: () -> Unit, onRetake: () -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(AC.Red.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            AIconView(AIcon.CROSS_CIRCLE, AC.Red, Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Could not analyse this image", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Color.White.copy(alpha = 0.8f), fontSize = 13.5.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (retryable) {
            PrimaryButton("Try again", onRetry, Modifier.fillMaxWidth(), icon = AIcon.REFRESH, height = 48.dp)
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Take new photo", onRetake, Modifier.weight(1f), fg = Color.White, bg = Color.White.copy(alpha = 0.14f), height = 46.dp)
            SoftButton("Upload instead", onGallery, Modifier.weight(1f), fg = Color.White, bg = Color.White.copy(alpha = 0.14f), height = 46.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Close", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onClose() }.padding(12.dp))
    }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

@Composable
private fun ResultView(
    case: ScanTarget,
    report: PackageReport,
    save: SaveState?,
    exporter: ReportExporter,
    awaitingConfirm: Boolean,
    resolving: Boolean,
    onConfirm: () -> Unit,
    onChangeCategory: (() -> Unit)?,
    onResolve: ((List<RuleDecision>) -> Unit)?,
    onRetrySave: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(AC.page())) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader("Package result", report.verdictLabel, onClose)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (awaitingConfirm) ConfirmCategoryBanner(report.classification, onConfirm, onChangeCategory)
                else SaveBanner(save, case.savedNote, onRetrySave, onOpen)
                PackageReportContent(report, null, onChangeCategory, resolving, onResolve)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val summary = (save as? SaveState.Saved)?.scan?.let {
                        ScanSummary(it.scanCode, it.verdict, it.passed, it.failed, it.review, report.notApplicable, it.score,
                            report.qualityGrade, "", report.analyzerVersion, report.durationMs, report.generatedAt, emptyList())
                    }
                    SoftButton("PDF", { exporter.packagePdf(case.inspection, summary, report) }, Modifier.weight(1f), icon = AIcon.DOWNLOAD,
                        enabled = exporter.busy == null, height = 42.dp)
                    SoftButton("JSON", { exporter.packageJson(summary, report) }, Modifier.weight(1f), icon = AIcon.DOC,
                        enabled = exporter.busy == null, height = 42.dp)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SoftButton("Done", onClose, Modifier.weight(1f), height = 48.dp)
                PrimaryButton("Scan next package", onNext, Modifier.weight(1.6f), icon = AIcon.PLUS, height = 48.dp,
                    enabled = save !is SaveState.Saving && !awaitingConfirm)
            }
        }
    }
}

@Composable
private fun ConfirmCategoryBanner(c: Classification?, onConfirm: () -> Unit, onChange: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AC.AmberSoft).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.HOURGLASS, AC.Amber, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (c?.source == "fallback") "The product could not be categorised. Choose its category so the right standards are verified."
                else "Is this ${c?.categoryName?.lowercase(Locale.ENGLISH) ?: "the right category"}? Confirm before the result is saved.",
                color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onChange != null) SoftButton("Choose category", onChange, Modifier.weight(1f), icon = AIcon.BOX, height = 40.dp)
            PrimaryButton(if (c?.source == "fallback") "Save as general" else "Confirm & save", onConfirm, Modifier.weight(1f),
                icon = AIcon.CHECK_CIRCLE, height = 40.dp)
        }
    }
}

@Composable
private fun SaveBanner(save: SaveState?, note: String, onRetry: () -> Unit, onOpen: () -> Unit) {
    val (tone, text) = when (save) {
        null, SaveState.Saving -> AC.Blue to "Saving result to your records…"
        is SaveState.Saved -> AC.Green to "Saved as ${save.scan.scanCode} • $note"
        is SaveState.Error -> AC.Red to "Not saved: ${save.message}"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.1f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (save == null || save == SaveState.Saving) CircularProgressIndicator(color = tone, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else AIconView(if (save is SaveState.Saved) AIcon.CHECK_CIRCLE else AIcon.CROSS_CIRCLE, tone, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        when (save) {
            is SaveState.Error -> Text("Retry", color = AC.Blue, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onRetry() }.padding(8.dp))
            is SaveState.Saved -> Text("Open", color = AC.Blue, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onOpen() }.padding(8.dp))
            else -> Unit
        }
    }
}
