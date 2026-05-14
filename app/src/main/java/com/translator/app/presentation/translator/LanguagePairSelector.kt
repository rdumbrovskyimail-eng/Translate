package com.translator.app.presentation.translator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.translator.app.domain.model.Language
import com.translator.app.domain.model.Languages
import kotlinx.coroutines.launch

@Composable
fun LanguagePairSelector(
    source: Language,
    target: Language,
    onSourceChange: (Language) -> Unit,
    onTargetChange: (Language) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerFor by remember { mutableStateOf<PickerSide?>(null) }
    var showSameWarning by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val swapRotation by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 300),
        label = "swap"
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LanguageChip(
                language = source,
                modifier = Modifier.weight(1f),
                onClick = { pickerFor = PickerSide.SOURCE }
            )

            IconButton(
                onClick = {
                    onSwap()
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Поменять языки местами",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.rotate(swapRotation)
                )
            }

            LanguageChip(
                language = target,
                modifier = Modifier.weight(1f),
                onClick = { pickerFor = PickerSide.TARGET }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    pickerFor?.let { side ->
        val excluded = if (side == PickerSide.SOURCE) target.code else source.code
        LanguagePickerDialog(
            currentCode = if (side == PickerSide.SOURCE) source.code else target.code,
            excludedCode = excluded,
            onDismiss = { pickerFor = null },
            onPick = { lang ->
                if (lang.code == excluded) {
                    showSameWarning = true
                    scope.launch {
                        snackbarHostState.showSnackbar("Выбраны одинаковые языки — перевод невозможен")
                    }
                } else {
                    if (side == PickerSide.SOURCE) onSourceChange(lang) else onTargetChange(lang)
                }
                pickerFor = null
            }
        )
    }
}

@Composable
private fun LanguageChip(
    language: Language,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = language.flag,
                fontSize = 20.sp
            )
            Text(
                text = language.nameRu,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    currentCode: String,
    excludedCode: String,
    onDismiss: () -> Unit,
    onPick: (Language) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) Languages.ALL
        else Languages.ALL.filter {
            it.nameRu.lowercase().contains(q) || it.nameEn.lowercase().contains(q)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Выберите язык",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.code }) { lang ->
                        LanguageRow(
                            language = lang,
                            isSelected = lang.code == currentCode,
                            isDisabled = lang.code == excludedCode,
                            onClick = { onPick(lang) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: Language,
    isSelected: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isDisabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val textColor = when {
        isDisabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(enabled = !isDisabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = language.flag, fontSize = 22.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = language.nameRu,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
            Text(
                text = language.nameEn,
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.6f)
            )
        }
        if (isDisabled) {
            Text(
                text = "уже выбран",
                fontSize = 11.sp,
                color = textColor
            )
        }
    }
}

private enum class PickerSide { SOURCE, TARGET }