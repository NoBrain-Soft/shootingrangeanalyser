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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.nobrainsoft.rangeanalyser.core.model.Profile
import com.nobrainsoft.rangeanalyser.core.model.ShootingPosition
import com.nobrainsoft.rangeanalyser.core.model.SightType
import com.nobrainsoft.rangeanalyser.core.model.SupportType
import com.nobrainsoft.rangeanalyser.ui.common.LabeledTextField
import com.nobrainsoft.rangeanalyser.ui.common.PickerField
import com.nobrainsoft.rangeanalyser.ui.common.StepperField
import com.nobrainsoft.rangeanalyser.ui.common.enumLabel
import com.nobrainsoft.rangeanalyser.ui.rangeViewModel
import com.nobrainsoft.rangeanalyser.ui.theme.Dimens

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
                viewModel.updateFirearm(current.copy(sight = current.sight.copy(type = it)))
            },
        )

        // The one field people get wrong, so it gets an explanation rather than a bare number.
        Text(
            "Sight adjustment",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Dimens.itemSpacing),
        )
        Text(
            "How far one click moves the point of impact. It is printed on the turret or in the " +
                "manual - usually as a fraction of a MOA, a fraction of a mil, or a distance at a " +
                "reference range. Getting the unit wrong is the commonest reason sight advice " +
                "sends people the wrong way.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val click = current.sight.clickValue ?: ClickValue.QUARTER_MOA
        StepperField(
            label = "Click size",
            value = click.amount,
            step = 0.05,
            range = 0.01..50.0,
            onValueChange = {
                viewModel.updateFirearm(
                    current.copy(sight = current.sight.copy(clickValue = click.copy(amount = it))),
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
                    current.copy(sight = current.sight.copy(clickValue = click.copy(unit = it))),
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

        StepperField(
            label = "Barrel length (mm)",
            value = current.barrelLengthMm ?: 600.0,
            step = 10.0,
            range = 50.0..1200.0,
            onValueChange = { viewModel.updateFirearm(current.copy(barrelLengthMm = it)) },
        )

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

        StepperField(
            label = "Muzzle velocity (m/s)",
            value = current.muzzleVelocityMps ?: 800.0,
            step = 5.0,
            range = 50.0..1400.0,
            onValueChange = { viewModel.updateAmmo(current.copy(muzzleVelocityMps = it)) },
        )

        // The field that unlocks the most useful coaching, so it says why it is worth filling in.
        Text(
            "Velocity consistency",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Dimens.itemSpacing),
        )
        Text(
            "If you have chronograph data, the standard deviation here lets the app work out how " +
                "much of your vertical spread is the ammunition rather than you - which is the " +
                "difference between practising harder and buying better ammunition.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepperField(
            label = "Velocity SD (m/s)",
            value = current.velocitySdMps ?: 0.0,
            step = 0.5,
            range = 0.0..60.0,
            onValueChange = { viewModel.updateAmmo(current.copy(velocitySdMps = it)) },
            format = { String.format(java.util.Locale.ROOT, "%.1f", it) },
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
        )

        PickerField(
            label = "Load",
            selected = compatibleAmmo.firstOrNull { it.id == current.ammoId },
            options = compatibleAmmo,
            optionLabel = { it.displayName },
            onSelect = { viewModel.updateProfile(current.copy(ammoId = it.id)) },
            placeholder = "Optional",
            onAddNew = onAddAmmo,
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
