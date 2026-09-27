package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Only regular actions use these variants; Home's connection control stays unique. */
enum class AfterglowButtonStyle { Primary, Accent, Secondary, Destructive }

@Composable
fun AfterglowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AfterglowButtonStyle = AfterglowButtonStyle.Primary,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = AfterglowTheme.colors
    val background =
        when (style) {
            AfterglowButtonStyle.Primary -> c.ink
            AfterglowButtonStyle.Accent -> c.coral
            AfterglowButtonStyle.Secondary -> c.paperSecondary
            AfterglowButtonStyle.Destructive -> c.error
        }
    val foreground =
        if (style == AfterglowButtonStyle.Secondary) {
            c.ink
        } else if (style == AfterglowButtonStyle.Primary) {
            c.paper
        } else {
            c.onInk
        }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = AfterglowTokens.buttonHeight),
        enabled = enabled,
        shape = AfterglowTokens.inputShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = background,
                contentColor = foreground,
                disabledContainerColor = c.paperSecondary,
                disabledContentColor = c.muted,
            ),
        content = content,
    )
}

@Composable
fun AfterglowCard(
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AfterglowTheme.colors
    Card(
        modifier = modifier,
        shape = AfterglowTokens.cardShape,
        border = BorderStroke(AfterglowTokens.border, if (interactive) c.ink else c.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = if (interactive) c.paper else c.paperSecondary),
    ) {
        Column(Modifier.padding(AfterglowTokens.cardPadding)) { content() }
    }
}

@Composable
fun AfterglowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = AfterglowTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = AfterglowTokens.fieldHeight),
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = AfterglowTokens.inputShape,
        colors = afterglowInputColors(c),
    )
}

@Composable
fun AfterglowTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = AfterglowTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = AfterglowTokens.fieldHeight),
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = AfterglowTokens.inputShape,
        colors = afterglowInputColors(c),
    )
}

@Composable
private fun afterglowInputColors(c: AfterglowPalette) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.coral,
        unfocusedBorderColor = c.border,
        errorBorderColor = c.error,
        cursorColor = c.coral,
        errorCursorColor = c.error,
        focusedContainerColor = c.paperSecondary,
        unfocusedContainerColor = c.paperSecondary,
        disabledContainerColor = c.paperSecondary,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedPlaceholderColor = c.muted,
        unfocusedPlaceholderColor = c.muted,
    )

@Composable
fun AfterglowChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val c = AfterglowTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = AfterglowTokens.touchTarget),
        label = label,
        leadingIcon = leadingIcon,
        shape = AfterglowTokens.chipShape,
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = c.border,
                selectedBorderColor = c.ink,
            ),
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = c.paperSecondary,
                labelColor = c.ink,
                selectedContainerColor = c.ink,
                selectedLabelColor = c.paper,
                selectedLeadingIconColor = c.paper,
            ),
    )
}

@Composable
fun AfterglowSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = AfterglowTheme.colors
    Switch(
        checked,
        onCheckedChange,
        modifier = modifier,
        colors =
            SwitchDefaults.colors(
                checkedTrackColor = c.coral,
                checkedThumbColor = c.paper,
                uncheckedTrackColor = c.paperSecondary,
                uncheckedThumbColor = c.muted,
                uncheckedBorderColor = c.border,
            ),
    )
}

@Composable
fun AfterglowRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = AfterglowTheme.colors
    RadioButton(
        selected,
        onClick,
        modifier = modifier,
        colors = RadioButtonDefaults.colors(selectedColor = c.coral, unselectedColor = c.muted),
    )
}

@Composable
fun AfterglowCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = AfterglowTheme.colors
    Checkbox(
        checked,
        onCheckedChange,
        modifier = modifier,
        colors =
            CheckboxDefaults.colors(
                checkedColor = c.coral,
                uncheckedColor = c.muted,
                checkmarkColor = c.onInk,
            ),
    )
}

@Composable
fun AfterglowDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val c = AfterglowTheme.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = AfterglowTokens.dialogShape,
        containerColor = c.paper,
        titleContentColor = c.ink,
        textContentColor = c.ink,
        tonalElevation = 0.dp,
    )
}
