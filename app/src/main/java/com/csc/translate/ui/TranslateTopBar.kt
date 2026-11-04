package com.csc.translate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csc.translate.R
import com.csc.translate.ui.theme.Ink

@Composable
fun TranslateTopBar(
    sourceLabel: String,
    targetLabel: String,
    swapEnabled: Boolean,
    onBack: () -> Unit,
    onSwap: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButtonCircle(onClick = onBack, label = stringResource(R.string.back)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = Ink)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sourceLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(32.dp)
                    .clickable(enabled = swapEnabled, role = Role.Button, onClick = onSwap),
                shape = CircleShape,
                color = Ink,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = stringResource(R.string.swap_direction),
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(targetLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
        }
        IconButtonCircle(onClick = onHistory, label = stringResource(R.string.history)) {
            Icon(Icons.Outlined.History, contentDescription = null, tint = Ink)
        }
    }
}

@Composable
fun IconButtonCircle(
    onClick: () -> Unit,
    label: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
