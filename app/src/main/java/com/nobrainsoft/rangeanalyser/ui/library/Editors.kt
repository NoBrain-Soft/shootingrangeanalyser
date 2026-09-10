// TopAppBar is still @ExperimentalMaterial3Api in Material3 1.3. Opted in at file level
// rather than per function: every top-level screen here has an app bar.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.nobrainsoft.rangeanalyser.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobrainsoft.rangeanalyser.core.model.ActionType
import com.nobrainsoft.rangeanalyser.core.model.Ammo
import com.nobrainsoft.rangeanalyser.core.model.BulletType
import com.nobrainsoft.rangeanalyser.core.model.Calibers
import com.nobrainsoft.rangeanalyser.core.model.ClickUnit
import com.nobrainsoft.rangeanalyser.core.model.ClickValue
import com.nobrainsoft.rangeanalyser.core.model.DragModel
import com.nobrainsoft.rangeanalyser.core.model.Firearm
import com.nobrainsoft.rangeanalyser.core.model.FirearmType
import com.nobrainsoft.rangeanalyser.core.model.Handedness
import com.nobrainsoft.rangeanalyser.core.model.MuzzleVelocity
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.ShootingPosition
import com.nobrainsoft.rangeanalyser.core.model.SightType
import com.nobrainsoft.rangeanalyser.core.model.SupportType
import com.nobrainsoft.rangeanalyser.core.model.VelocityConfidence
import com.nobrainsoft.rangeanalyser.ui.common.LabeledTextField
import com.nobrainsoft.rangeanalyser.ui.common.PickerField
import com.nobrainsoft.rangeanalyser.ui.common.StepperField
import com.nobrainsoft.rangeanalyser.ui.common.enumLabel
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun FirearmEditorScreen(firearmId: String?, onDone: () -> Unit) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    LaunchedEffect(firearmId) { viewModel.loadFirearm(firearmId) }

    val firearm by viewModel.firearm.collectAsStateWithLifecycle()
    val current = firearm ?: return

    EditorScaffold(
        title = if (firearmId == null) "New firearm" else "Edit firearm",
        onBack = onDone,
        onDelete = if (firearmId == null) null else {
            {
                viewModel.deleteFirearm(current)
                onDone()
            }
        },
        onSave = {
            viewModel.saveFirearm(current)
            onDone()
        },
        saveEnabled = current.name.isNotBlank(),
    ) {
        LabeledTextField(
            label = "Name",
            value = current.name,
            onValueChange = { viewModel.updateFirearm(current.copy(name = it)) },
            supporting = "What you call it - \"Match rifle\", \"Carry gun\"",
        )

        PickerField(
            label = "Type",
            selected = current.type,
            options = FirearmType.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = { viewModel.updateFirearm(current.copy(type = it)) },
        )

        PickerField(
            label = "Calibre",
            selected = Calibers.find(current.caliberId),
            options = Calibers.all,
            optionLabel = { it.displayName },
            optionDescription = { "${it.bulletDiameterMm} mm bullet" },
            onSelect = { viewModel.updateFirearm(current.copy(caliberId = it.id)) },
        )

        PickerField(
            label = "Action",
            selected = current.action,
            options = ActionType.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = { viewModel.updateFirearm(current.copy(action = it)) },
        )

        PickerField(
            label = "Sight",
            selected = current.sight.type,
            options = SightType.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = {
                // Switching to a sight that has nothing to turn drops the click settings with it,
                // rather than leaving a stale number behind that would produce confident nonsense.
                val sight = current.sight.copy(type = it)
                viewModel.updateFirearm(
                    current.copy(
                        sight = if (it == SightType.NONE) sight.copy(clickValue = null) else sight,
                    ),
                )
            },
        )

        StepperField(
            label = "Barrel length (mm)",
            value = current.barrelLengthMm ?: defaultBarrelLengthMm(current.type),
            step = 10.0,
            range = 50.0..1200.0,
            onValueChange = { viewModel.updateFirearm(current.copy(barrelLengthMm = it)) },
            supporting = "Used to estimate what your ammunition is doing",
        )

        // Everything below is only meaningful for a sight you can actually adjust. Shown to
        // everyone, it was the most complicated part of setting up a gun and pure noise for irons
        // and for open sights that no click count applies to.
        if (current.sight.type != SightType.NONE) {
            val adjustable = current.sight.clickValue != null

            ListItem(
                headlineContent = { Text("This sight has click adjustments") },
                supportingContent = {
                    Text(
                        if (adjustable) {
                            "The app can then work out how many clicks to move your zero."
                        } else {
                            "Leave off for fixed sights. Everything else still works - you just " +
                                "get the group's offset in millimetres instead of a click count."
                        },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = adjustable,
                        onCheckedChange = { wanted ->
                            viewModel.updateFirearm(
                                current.copy(
                                    sight = current.sight.copy(
                                        clickValue = if (wanted) ClickValue.QUARTER_MOA else null,
                                    ),
                                ),
                            )
                        },
                    )
                },
            )

            current.sight.clickValue?.let { click ->
                // The one field people get wrong, so it gets an explanation rather than a bare
                // number.
                Text(
                    "How far one click moves the point of impact. It is printed on the turret or " +
                        "in the manual - usually as a fraction of a MOA, a fraction of a mil, or " +
                        "a distance at a reference range. Getting the unit wrong is the commonest " +
                        "reason sight advice sends people the wrong way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                StepperField(
                    label = "Click size",
                    value = click.amount,
                    step = 0.05,
                    range = 0.01..50.0,
                    onValueChange = {
                        viewModel.updateFirearm(
                            current.copy(
                                sight = current.sight.copy(clickValue = click.copy(amount = it)),
                            ),
                        )
                    },
                    format = { String.format(java.util.Locale.ROOT, "%.2f", it) },
                )

                PickerField(
                    label = "Click unit",
                    selected = click.unit,
                    options = ClickUnit.entries,
                    optionLabel = {
                        when (it) {
                            ClickUnit.MOA -> "MOA"
                            ClickUnit.MIL -> "Milliradian"
                            ClickUnit.MM_AT_100M -> "mm at 100 m"
                            ClickUnit.INCH_AT_100YD -> "inch at 100 yd"
                        }
                    },
                    onSelect = {
                        viewModel.updateFirearm(
                            current.copy(
                                sight = current.sight.copy(clickValue = click.copy(unit = it)),
                            ),
                        )
                    },
                )

                StepperField(
                    label = "Zero distance (m)",
                    value = current.zeroDistanceM ?: 100.0,
                    step = 5.0,
                    range = 1.0..1000.0,
                    onValueChange = { viewModel.updateFirearm(current.copy(zeroDistanceM = it)) },
                )

                if (current.sight.type == SightType.SCOPE ||
                    current.sight.type == SightType.RED_DOT
                ) {
                    StepperField(
                        label = "Sight height above bore (mm)",
                        value = current.sight.opticHeightMm ?: 40.0,
                        step = 1.0,
                        range = 0.0..150.0,
                        onValueChange = {
                            viewModel.updateFirearm(
                                current.copy(sight = current.sight.copy(opticHeightMm = it)),
                            )
                        },
                    )
                }
            }
        }

        LabeledTextField(
            label = "Notes",
            value = current.notes,
            onValueChange = { viewModel.updateFirearm(current.copy(notes = it)) },
            singleLine = false,
        )
    }
}

@Composable
fun AmmoEditorScreen(ammoId: String?, onDone: () -> Unit) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    LaunchedEffect(ammoId) { viewModel.loadAmmo(ammoId) }

    val ammo by viewModel.ammo.collectAsStateWithLifecycle()
    val current = ammo ?: return

    EditorScaffold(
        title = if (ammoId == null) "New load" else "Edit load",
        onBack = onDone,
        onDelete = if (ammoId == null) null else {
            {
                viewModel.deleteAmmo(current)
                onDone()
            }
        },
        onSave = {
            viewModel.saveAmmo(current)
            onDone()
        },
        saveEnabled = current.brand.isNotBlank(),
    ) {
        LabeledTextField(
            label = "Brand",
            value = current.brand,
            onValueChange = { viewModel.updateAmmo(current.copy(brand = it)) },
        )
        LabeledTextField(
            label = "Line",
            value = current.line,
            onValueChange = { viewModel.updateAmmo(current.copy(line = it)) },
            supporting = "\"Gold Medal Match\", \"Bulk\"",
        )

        PickerField(
            label = "Calibre",
            selected = Calibers.find(current.caliberId),
            options = Calibers.all,
            optionLabel = { it.displayName },
            onSelect = { viewModel.updateAmmo(current.copy(caliberId = it.id)) },
        )

        PickerField(
            label = "Bullet type",
            selected = current.bulletType,
            options = BulletType.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = { viewModel.updateAmmo(current.copy(bulletType = it)) },
        )

        StepperField(
            label = "Bullet weight (grains)",
            value = current.bulletWeightGrains ?: 150.0,
            step = 1.0,
            range = 1.0..800.0,
            onValueChange = { viewModel.updateAmmo(current.copy(bulletWeightGrains = it)) },
        )

        VelocitySection(
            ammo = current,
            onChange = { viewModel.updateAmmo(it) },
        )

        StepperField(
            label = "Ballistic coefficient",
            value = current.ballisticCoefficient ?: 0.400,
            step = 0.005,
            range = 0.005..1.5,
            onValueChange = { viewModel.updateAmmo(current.copy(ballisticCoefficient = it)) },
            format = { String.format(java.util.Locale.ROOT, "%.3f", it) },
        )

        PickerField(
            label = "Drag model",
            selected = current.dragModel,
            options = DragModel.entries,
            optionLabel = { it.name },
            optionDescription = {
                when (it) {
                    DragModel.G1 -> "What most factory boxes quote"
                    DragModel.G7 -> "Better for boat-tail match bullets"
                }
            },
            onSelect = { viewModel.updateAmmo(current.copy(dragModel = it)) },
        )

        LabeledTextField(
            label = "Lot number",
            value = current.lot,
            onValueChange = { viewModel.updateAmmo(current.copy(lot = it)) },
            supporting = "Worth recording - lots do shoot differently",
        )
    }
}

@Composable
fun ProfileEditorScreen(
    profileId: String?,
    onDone: () -> Unit,
    onAddFirearm: () -> Unit,
    onAddAmmo: () -> Unit,
    onEditFirearm: (String) -> Unit,
    onEditAmmo: (String) -> Unit,
    onEditTargets: () -> Unit,
) {
    val viewModel = rangeViewModel(key = "library") { LibraryViewModel(it.repository) }
    LaunchedEffect(profileId) { viewModel.loadProfile(profileId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val current = profile ?: return

    val selectedFirearm = state.firearms.firstOrNull { it.id == current.firearmId }
    // Only offer ammunition that fits the gun. Nothing else is a plausible mistake to allow.
    val compatibleAmmo = selectedFirearm
        ?.let { firearm -> state.ammo.filter { it.caliberId == firearm.caliberId } }
        ?: state.ammo

    EditorScaffold(
        title = if (profileId == null) "New setup" else "Edit setup",
        onBack = onDone,
        onDelete = if (profileId == null) null else {
            {
                viewModel.deleteProfile(current)
                onDone()
            }
        },
        onSave = {
            viewModel.saveProfile(current)
            onDone()
        },
        saveEnabled = current.name.isNotBlank() && current.firearmId.isNotBlank(),
    ) {
        LabeledTextField(
            label = "Setup name",
            value = current.name,
            onValueChange = { viewModel.updateProfile(current.copy(name = it)) },
            supporting = "\"Club 25m\", \"Load testing\"",
        )

        PickerField(
            label = "Firearm",
            selected = selectedFirearm,
            options = state.firearms,
            optionLabel = { it.name.ifBlank { "Unnamed" } },
            optionDescription = { Calibers.find(it.caliberId)?.displayName },
            onSelect = {
                // Changing the gun can invalidate the load, so clear it rather than leave a
                // mismatch that would silently produce wrong ballistics.
                val keepAmmo = current.ammoId?.takeIf { ammoId ->
                    state.ammo.firstOrNull { a -> a.id == ammoId }?.caliberId == it.caliberId
                }
                viewModel.updateProfile(current.copy(firearmId = it.id, ammoId = keepAmmo))
            },
            onAddNew = onAddFirearm,
            onEditSelected = selectedFirearm?.let { firearm -> { onEditFirearm(firearm.id) } },
        )

        PickerField(
            label = "Load",
            selected = compatibleAmmo.firstOrNull { it.id == current.ammoId },
            options = compatibleAmmo,
            optionLabel = { it.displayName },
            onSelect = { viewModel.updateProfile(current.copy(ammoId = it.id)) },
            placeholder = "Optional",
            onAddNew = onAddAmmo,
            onEditSelected = current.ammoId?.let { id -> { onEditAmmo(id) } },
        )

        PickerField(
            label = "Target",
            selected = state.targets.firstOrNull { it.id == current.targetSpecId },
            options = state.targets,
            optionLabel = { it.name },
            optionDescription = {
                if (it.dimensionsVerified) null else "Dimensions approximate"
            },
            onSelect = {
                viewModel.updateProfile(
                    current.copy(
                        targetSpecId = it.id,
                        distanceM = it.defaultDistanceM ?: current.distanceM,
                    ),
                )
            },
            onAddNew = onEditTargets,
        )

        StepperField(
            label = "Distance (m)",
            value = current.distanceM,
            step = 5.0,
            range = 1.0..1000.0,
            onValueChange = { viewModel.updateProfile(current.copy(distanceM = it)) },
        )

        PickerField(
            label = "Position",
            selected = current.position,
            options = ShootingPosition.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = { viewModel.updateProfile(current.copy(position = it)) },
        )

        PickerField(
            label = "Support",
            selected = current.support,
            options = SupportType.entries,
            optionLabel = { enumLabel(it.name) },
            onSelect = { viewModel.updateProfile(current.copy(support = it)) },
        )

        PickerField(
            label = "Shooting hand",
            selected = current.handedness,
            options = Handedness.entries,
            optionLabel = { enumLabel(it.name) },
            optionDescription = {
                if (it == Handedness.UNKNOWN) {
                    "Fine to leave - some pistol tips are then phrased without a side"
                } else {
                    null
                }
            },
            onSelect = { viewModel.updateProfile(current.copy(handedness = it)) },
        )

        if (current.calibration != null) {
            Text(
                "A calibration is saved with this setup, so photo and live sessions start ready " +
                    "to measure. Re-run the wizard if you change target or move the camera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Dimens.itemSpacing),
            )
        }
    }
}

/**
 * Muzzle velocity, without demanding a chronograph.
 *
 * Asking outright for a number almost nobody can measure produced the worst possible outcome: a
 * made-up figure the app then treated as fact. So the estimate is shown as an estimate, with the
 * range it could plausibly be and where it came from, and the field for a real measurement only
 * appears if the shooter says they have one.
 */
@Composable
private fun VelocitySection(ammo: Ammo, onChange: (Ammo) -> Unit) {
    val estimate = remember(ammo.caliberId, ammo.bulletWeightGrains) {
        MuzzleVelocity.estimate(ammo.caliber(), ammo.bulletWeightGrains)
    }
    val measured = ammo.muzzleVelocityMps

    Text(
        "Speed",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Dimens.itemSpacing),
    )

    if (estimate != null && measured == null) {
        Text(
            String.format(
                java.util.Locale.ROOT,
                "Estimated at %.0f m/s, plausibly %.0f-%.0f.",
                estimate.metresPerSecond,
                estimate.plausibleLowMps,
                estimate.plausibleHighMps,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            estimate.basis + when (estimate.confidence) {
                VelocityConfidence.TYPICAL ->
                    " Close enough for working out how much of your vertical spread is the load."
                VelocityConfidence.BROAD ->
                    " This calibre covers very different loadings, so treat it loosely."
                VelocityConfidence.GUN_DEPENDENT ->
                    " Airgun velocity is a property of the gun, not the pellet - this is barely " +
                        "more than a starting point."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (estimate == null && measured == null) {
        Text(
            "No reference figure for this calibre, so speed is left unknown rather than guessed. " +
                "Coaching that needs it will say so instead of using a made-up number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ListItem(
        headlineContent = { Text("I have chronograph figures") },
        supportingContent = {
            Text(
                "A measured speed and, better still, its standard deviation. The SD is what lets " +
                    "the app separate vertical spread the ammunition caused from spread you did.",
            )
        },
        trailingContent = {
            Switch(
                checked = measured != null,
                onCheckedChange = { wanted ->
                    onChange(
                        if (wanted) {
                            ammo.copy(
                                muzzleVelocityMps = estimate?.metresPerSecond?.let {
                                    (it / 5.0).roundToInt() * 5.0
                                } ?: 350.0,
                            )
                        } else {
                            ammo.copy(muzzleVelocityMps = null, velocitySdMps = null)
                        },
                    )
                },
            )
        },
    )

    if (measured != null) {
        StepperField(
            label = "Muzzle velocity (m/s)",
            value = measured,
            step = 5.0,
            range = 50.0..1400.0,
            onValueChange = { onChange(ammo.copy(muzzleVelocityMps = it)) },
        )
        StepperField(
            label = "Velocity SD (m/s)",
            value = ammo.velocitySdMps ?: 0.0,
            step = 0.5,
            range = 0.0..60.0,
            onValueChange = { onChange(ammo.copy(velocitySdMps = it)) },
            format = { String.format(java.util.Locale.ROOT, "%.1f", it) },
            supporting = "Leave at zero if you only have an average",
        )
    }
}

/** A sensible starting barrel length, so the field is not a blank demand either. */
private fun defaultBarrelLengthMm(type: FirearmType): Double = when (type) {
    FirearmType.PISTOL, FirearmType.RIMFIRE_PISTOL, FirearmType.AIR_PISTOL -> 110.0
    FirearmType.REVOLVER -> 150.0
    FirearmType.SHOTGUN -> 710.0
    FirearmType.AIR_RIFLE -> 450.0
    FirearmType.RIMFIRE_RIFLE -> 500.0
    FirearmType.CENTREFIRE_RIFLE -> 600.0
}

@Composable
private fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    onDelete: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    onDelete?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.gutter)
                    .heightIn(min = Dimens.touchTargetRange),
            ) {
                Text("Save")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter, vertical = Dimens.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            content()
            Spacer(Modifier.height(Dimens.sectionSpacing))
        }
    }
}
