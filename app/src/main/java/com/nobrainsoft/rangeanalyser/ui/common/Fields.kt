package com.nobrainsoft.rangeanalyser.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    numeric: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget),
    )
}

/**
 * A value picked from a list, opened as a full dialog rather than an inline dropdown.
 *
 * A dropdown that unrolls under a finger is fiddly outdoors; a dialog gives every option a
 * full-width row that is hard to miss.
 */
@Composable
fun <T> PickerField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionDescription: ((T) -> String?)? = null,
    placeholder: String = "Choose",
    onAddNew: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTarget)
                .padding(top = 4.dp)
                .clickable { open = true },
            shape = RoundedCornerShape(Dimens.cardCorner),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = Dimens.gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selected?.let(optionLabel) ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn {
                    items(options) { option ->
                        ChoiceRow(
                            label = optionLabel(option),
                            description = optionDescription?.invoke(option),
                            selected = option == selected,
                            onClick = {
                                onSelect(option)
                                open = false
                            },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                // "Add new" lives inside the picker so a missing entry never dead-ends the form.
                onAddNew?.let {
                    TextButton(onClick = {
                        open = false
                        it()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add new", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        )
    }
}

/** A row of mutually exclusive choices, for short option sets. */
@Composable
fun <T> ChipPicker(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTarget)
                        .clickable { onSelect(option) },
                    shape = RoundedCornerShape(Dimens.cardCorner),
                    color = if (option == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            optionLabel(option),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (option == selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Turns an enum constant into something readable: BREAK_BARREL becomes "Break barrel". */
fun enumLabel(name: String): String =
    name.lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
