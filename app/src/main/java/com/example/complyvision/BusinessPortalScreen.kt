package com.example.complyvision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Business Portal: hero, submodule cards and a closing banner.
 * Everything flows in a single scrolling column so no section can overlap another.
 */
@Composable
fun BusinessPortalHomeScreen(
    onNavigate: (AppScreen) -> Unit,
    onBack: () -> Unit
) {
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
        ) {
            PortalHero(onBack = onBack) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF050B18))) { append("Business\n") }
                        withStyle(SpanStyle(color = PortalBlue)) { append("Portal") }
                    },
                    fontSize = 37.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 37.sp,
                    letterSpacing = (-1).sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Compliance solutions for manufacturers, importers, retailers and regulatory teams.",
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    color = PortalMuted,
                    modifier = Modifier.widthIn(max = 190.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PortalSubmoduleCard(
                    title = "Legal Metrology",
                    badgeText = "LM",
                    description = "Verify packaged commodity declarations as per Legal Metrology standards.",
                    bgDrawableId = R.drawable.card_lm_bg,
                    bgAspect = 1600f / 535f,
                    tileCenterFrac = 0.407f,
                    tileLeftFrac = 0.039f,
                    bgZoom = 1.28f,
                    accentColor = Color(0xFF1D4ED8),
                    badgeBackground = Color(0xFFD6E7FF),
                    shadowColor = Color(0xFF2F6FE0),
                    onClick = { onNavigate(AppScreen.OFFICER_LOGIN) }
                )

                PortalSubmoduleCard(
                    title = "Organisation\nPre-Verification",
                    badgeText = "ORG",
                    description = "Verify organisation compliance documents before regulatory submission.",
                    bgDrawableId = R.drawable.card_bis_bg,
                    bgAspect = 1600f / 533f,
                    tileCenterFrac = 0.5f,
                    tileLeftFrac = 0f,
                    bgZoom = 1f,
                    accentColor = Color(0xFFEA580C),
                    badgeBackground = Color(0xFFFFE0CC),
                    shadowColor = Color(0xFFE59A5E),
                    leadingIcon = { cardWidth -> OrganisationTile(cardWidth = cardWidth) },
                    onClick = { onNavigate(AppScreen.ORG_PRE_VERIFICATION_HOME) }
                )

                PortalFooterBanner()

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

/**
 * One submodule row: tinted background art (icon tile baked in, or drawn via [leadingIcon]),
 * title + code badge, description and a round chevron button.
 * The row never truncates its text; it grows taller when a title or description needs more lines.
 */
@Composable
private fun PortalSubmoduleCard(
    title: String,
    badgeText: String,
    description: String,
    bgDrawableId: Int,
    bgAspect: Float,
    tileCenterFrac: Float,
    tileLeftFrac: Float,
    bgZoom: Float,
    accentColor: Color,
    badgeBackground: Color,
    shadowColor: Color,
    leadingIcon: (@Composable BoxScope.(Dp) -> Unit)? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardW = maxWidth
        val minH = cardW * (225f / 826f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minH)
                .shadow(
                    6.dp, shape,
                    ambientColor = shadowColor.copy(alpha = 0.25f),
                    spotColor = shadowColor.copy(alpha = 0.25f)
                )
                .clip(shape)
                .background(Color(0xFFF3F8FE))
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape)
                .clickable(role = Role.Button) { onClick() }
        ) {
            // Background art, zoomed around its icon tile so the tile matches the reference size
            Box(modifier = Modifier.matchParentSize().clipToBounds()) {
                val imgH = cardW / bgAspect
                val offsetY = (imgH * tileCenterFrac - minH / 2)
                    .coerceIn(0.dp, (imgH - minH).coerceAtLeast(0.dp))
                Image(
                    painter = painterResource(id = bgDrawableId),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .requiredSize(cardW, imgH)
                        .offset(y = -offsetY)
                        .graphicsLayer {
                            scaleX = bgZoom
                            scaleY = bgZoom
                            transformOrigin = TransformOrigin(tileLeftFrac, tileCenterFrac)
                        }
                )
            }

            leadingIcon?.invoke(this, cardW)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = cardW * 0.267f, end = 12.dp, top = 10.dp, bottom = 10.dp)
                    .align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            color = Color(0xFF0B1B33),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = badgeBackground,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(top = 1.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = description,
                        fontSize = 12.sp,
                        lineHeight = 14.5.sp,
                        color = Color(0xFF6A7C98)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(
                            3.dp, CircleShape,
                            ambientColor = shadowColor.copy(alpha = 0.3f),
                            spotColor = shadowColor.copy(alpha = 0.3f)
                        )
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.8f))
                        .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ChevronIcon(color = accentColor, pointsLeft = false, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Peach tile with an organisation glyph, positioned like the icon tiles baked into the other card art */
@Composable
private fun BoxScope.OrganisationTile(cardWidth: Dp) {
    val tile = cardWidth * 0.187f
    val corner = RoundedCornerShape(tile * 0.22f)
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = cardWidth * 0.039f)
            .size(tile)
            .clip(corner)
            .background(Brush.verticalGradient(listOf(Color(0xFFFDEBDD), Color(0xFFFBDCC8))))
            .border(1.dp, Color.White.copy(alpha = 0.7f), corner),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(
            glyph = Glyph.ORGANISATION,
            tint = Color(0xFFE0561A),
            modifier = Modifier.fillMaxSize(0.6f)
        )
        // Small verified badge on the corner of the tile
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = tile * 0.12f, bottom = tile * 0.12f)
                .size(tile * 0.3f)
                .clip(CircleShape)
                .background(Color(0xFF1E9E55)),
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(glyph = Glyph.CHECK, tint = Color.White, modifier = Modifier.fillMaxSize(0.62f))
        }
    }
}

@Composable
private fun PortalFooterBanner() {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxWidth * (101f / 386f))
                .shadow(
                    6.dp, shape,
                    ambientColor = Color(0x331E5FB0),
                    spotColor = Color(0x331E5FB0)
                )
                .clip(shape)
                .background(Color(0xFFEAF3FD))
                .border(1.dp, Color.White.copy(alpha = 0.8f), shape)
        ) {
            Image(
                painter = painterResource(id = R.drawable.business_footer_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomEnd,
                modifier = Modifier.matchParentSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = "Ensure Compliance.\nBuild Consumer Trust.",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    color = Color(0xFF050B18)
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "Streamlined verification for a safer, healthier India.",
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF5E7390)
                )
            }
        }
    }
}
