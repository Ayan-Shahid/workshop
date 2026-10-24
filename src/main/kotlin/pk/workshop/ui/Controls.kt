package pk.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Primary button: mesh gradient of primary shades, flat 1 dp border, no elevation or shadow.
 * Same call shape as Material's Button, so screens use it unchanged.
 */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val dark = isDarkTheme()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .minimumInteractiveComponentSize()   // same click area as Material's buttons, so rows line up
            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
            .clip(shape)
            .then(if (enabled) Modifier.drawBehind {
                drawMesh(dark)
                // Flat state feedback: a light or dark wash, never a raised or sunken look
                if (pressed) drawRect(Color.Black.copy(alpha = 0.14f)) else if (hovered) drawRect(Color.White.copy(alpha = 0.10f))
            } else Modifier.background(cs.onSurface.copy(alpha = 0.10f)))
            .border(1.dp, if (enabled) meshBorderColor(dark) else cs.outlineVariant, shape)
            .hoverable(src, enabled)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) Color.White else cs.onSurface.copy(alpha = 0.38f),
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) { content() }
    }
}

/** Colours for every text box: solid fill, border colour by state. */
@Composable
fun fieldColors(hovered: Boolean = false): TextFieldColors {
    val cs = MaterialTheme.colorScheme
    val fill = if (isDarkTheme()) Color(0xFF232126) else Color.White
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, errorContainerColor = fill, disabledContainerColor = cs.surfaceVariant,
        focusedBorderColor = cs.primary, unfocusedBorderColor = if (hovered) cs.outline else cs.outlineVariant,
        errorBorderColor = cs.error, disabledBorderColor = cs.outlineVariant.copy(alpha = 0.6f),
        focusedTextColor = cs.onSurface, unfocusedTextColor = cs.onSurface, disabledTextColor = cs.onSurfaceVariant,
        cursorColor = cs.primary, errorCursorColor = cs.error,
        focusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.6f), unfocusedPlaceholderColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

/**
 * Text box with a persistent label above it (never floats into the box), a solid fill, and a border colour that
 * shows the state: light grey normal, darker grey under the mouse, primary while typing, error red when it needs fixing,
 * shaded fill when read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledBox(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1,
    enabled: Boolean = true, readOnly: Boolean = false, error: String? = null, placeholder: String? = null,
    trailing: (@Composable () -> Unit)? = null, visual: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier, interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    val colors = fieldColors(hovered)
    val labelColor = when { error != null -> cs.error; focused -> cs.primary; else -> cs.onSurfaceVariant }
    Column(modifier.width(IntrinsicSize.Max).padding(top = 4.dp)) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = labelColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, readOnly = readOnly, singleLine = singleLine, minLines = minLines,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE, visualTransformation = visual, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            interactionSource = interaction, cursorBrush = SolidColor(if (error != null) cs.error else cs.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) cs.onSurface else cs.onSurfaceVariant),
            modifier = fieldModifier.fillMaxWidth().defaultMinSize(minWidth = 120.dp).hoverable(interaction),
        ) { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value, innerTextField = inner, enabled = enabled, singleLine = singleLine, visualTransformation = visual,
                interactionSource = interaction, isError = error != null, placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
                trailingIcon = trailing, supportingText = error?.let { { Text(it) } }, colors = colors,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(enabled, error != null, interaction, colors, RoundedCornerShape(8.dp),
                        focusedBorderThickness = 2.dp, unfocusedBorderThickness = 1.dp)
                },
            )
        }
    }
}
