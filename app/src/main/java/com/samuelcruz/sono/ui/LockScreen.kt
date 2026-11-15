package com.samuelcruz.sono.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Covers the whole app until the fingerprint / face / PIN check passes. Asks right away when shown. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val activity = LocalActivity.current
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler { activity?.moveTaskToBack(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(LocalMoneyColors.current.hero)
            .pointerInput(Unit) { detectTapGestures { } }, // nothing behind can be touched
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("🔒", fontSize = 42.sp) }
            Spacer(Modifier.height(20.dp))
            Text("Sổ Nợ & Hụi đang khóa", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand),
                modifier = Modifier.height(52.dp),
            ) { Text("Mở khóa", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
