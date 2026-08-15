package indi.dmzz_yyhyy.lightnovelreader.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.explore.MultiChoiceExploreFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SliderFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SwitchFilter

@Composable
fun Filter<*>.Component(
    dialog: (@Composable () -> Unit) -> Unit,
    onChange: () -> Unit
) {
    val currentOnChange by rememberUpdatedState(onChange)
    remember(this) {
        addOnChangeListener(Int.MIN_VALUE) {
            currentOnChange()
        }
        true
    }
    when (this) {
        is SwitchFilter -> {
            var enabled by remember { mutableStateOf(this.value) }
            LaunchedEffect(this.value) {
                enabled = this@Component.value
            }
            BaseFilter(
                title = this.getTitle().resolve(),
                selected = enabled,
                onClick = {
                    enabled = !enabled
                    this.value = enabled
                }
            )
        }
        is MultiChoiceExploreFilter -> {
            var enabled by remember { mutableStateOf(this.value != setOf(this.defaultChoice)) }
            var displayDialog by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(this.value) }
            remember(this) {
                addOnChangeListener(Int.MIN_VALUE + 1) { changedValue ->
                    selected = changedValue
                    enabled = changedValue != setOf(defaultChoice)
                    displayDialog = false
                }
                true
            }
            LaunchedEffect(this.value) {
                selected = this@Component.value
                enabled = selected != setOf(this@Component.defaultChoice)
                displayDialog = false
            }
            LaunchedEffect(displayDialog) {
                if (displayDialog) {
                    dialog {
                        MultiFilterChipsDialog(
                            enable = displayDialog,
                            selected = selected,
                            title = this@Component.dialogTitle.resolve(),
                            description = this@Component.description.resolve(),
                            choices = this@Component.choices,
                            onSelectedChange = { choice ->
                                selected = if (choice == this@Component.defaultChoice) {
                                    setOf(choice)
                                } else {
                                    val explicitSelection = selected - this@Component.defaultChoice
                                    when {
                                        choice in explicitSelection -> explicitSelection - choice
                                        explicitSelection.size < this@Component.maxSelections ->
                                            explicitSelection + choice
                                        else -> explicitSelection
                                    }.let(this@Component::normalizeSelection)
                                }
                                enabled = selected != setOf(this@Component.defaultChoice)
                            },
                            onConfirmation = {
                                displayDialog = false
                                this@Component.value = this@Component.normalizeSelection(selected)
                            },
                            onDismissRequest = {
                                displayDialog = false
                                selected = this@Component.value
                            }
                        )
                    }
                } else {
                    dialog {}
                }
            }
            BaseFilter(
                title = "${this.getTitle().resolve()}: ${selected.joinToString("、")}",
                selected = enabled,
                onClick = { displayDialog = true }
            )
        }
        is SingleChoiceFilter -> {
            var enabled by remember { mutableStateOf(this.value != this.getDefaultChoice()) }
            var displayDialog by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(this.value) }
            remember(this) {
                addOnChangeListener(Int.MIN_VALUE + 1) { changedValue ->
                    selected = changedValue
                    enabled = changedValue != getDefaultChoice()
                    displayDialog = false
                }
                true
            }
            LaunchedEffect(this.value) {
                enabled = this@Component.value != this@Component.getDefaultChoice()
                displayDialog = false
                selected = this@Component.value
            }
            LaunchedEffect(displayDialog) {
                if (displayDialog)
                    dialog {
                        FilterChipsDialog(
                            enable = displayDialog,
                            selected = selected,
                            title = this@Component.dialogTitle.resolve(),
                            description = this@Component.description.resolve(),
                            onSelectedChange = {
                                selected = it
                                enabled = it != this@Component.getDefaultChoice()
                            },
                            choices = this@Component.getAllChoices(),
                            onConfirmation = {
                                displayDialog = false
                                this@Component.value = selected
                            },
                            onDismissRequest = {
                                displayDialog = false
                                selected = this@Component.value
                            },
                        )
                    }
                else
                    dialog {}
            }
            BaseFilter(
                title = "${this.getTitle().resolve()}: $selected",
                selected = enabled,
                onClick = {
                    displayDialog = true
                }
            )
        }
        is SliderFilter -> {
            var enabled by remember { mutableStateOf(this.enabled) }
            var displayDialog by remember { mutableStateOf(false) }
            var value by remember { mutableFloatStateOf(this.value) }
            LaunchedEffect(this@Component.enabled) {
                enabled = this@Component.enabled
            }
            LaunchedEffect(displayDialog) {
                dialog {
                    if (displayDialog)
                        SliderDialog(
                            onDismissRequest = {
                                displayDialog = false
                                value = this@Component.value
                            },
                            onConfirmation = {
                                displayDialog = false
                                this@Component.value = value
                            },
                            value = value,
                            valueRange = this@Component.valueRange,
                            steps = this@Component.steps,
                            onSlideChange = { value = it },
                            onSliderChangeFinished = {  },
                            title = this@Component.getTitle().resolve(),
                            description = this@Component.description
                        )
                }
            }
            BaseFilter(
                title = "${this.displayTitle.resolve()}: ${this.displayValue}",
                selected = enabled,
                onClick = {
                    displayDialog = true
                }
            )
        }
    }
}

@Composable
fun BaseFilter(
    modifier: Modifier = Modifier,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            AnimatedContent(
                targetState = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "FilterTitleColorAnime",
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W500,
                    color = it
                )
            }
        },
        modifier = modifier,
        leadingIcon = {
            AnimatedVisibility(
                visible = selected,
            ) {
                Icon(
                    modifier = Modifier.size(18.dp),
                    painter = painterResource(R.drawable.check_24px),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentDescription = null
                )
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiFilterChipsDialog(
    enable: Boolean,
    selected: Set<String>,
    title: String,
    description: String,
    choices: List<String>,
    onSelectedChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
) {
    AnimatedVisibility(
        visible = enable,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        BaseDialog(
            icon = painterResource(R.drawable.text_fields_24px),
            title = title,
            description = description,
            onDismissRequest = onDismissRequest,
            onConfirmation = onConfirmation,
            dismissText = stringResource(id = R.string.cancel),
            confirmationText = stringResource(id = R.string.apply),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 33.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    maxItemsInEachRow = 4
                ) {
                    choices.forEach { choice ->
                        val isSelected = choice in selected
                        FilterChip(
                            modifier = Modifier.padding(0.dp),
                            selected = isSelected,
                            onClick = { onSelectedChange(choice) },
                            label = {
                                AnimatedContent(
                                    targetState = if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    label = "MultiFilterTitleColorAnime",
                                ) {
                                    Text(
                                        text = choice,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.W500,
                                        color = it
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipsDialog(
    enable: Boolean,
    selected: String,
    title: String,
    description: String,
    choices: List<String>,
    onSelectedChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
) {
    AnimatedVisibility(
        visible = enable,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        BaseDialog(
            icon = painterResource(R.drawable.text_fields_24px),
            title = title,
            description = description,
            onDismissRequest = onDismissRequest,
            onConfirmation = onConfirmation,
            dismissText = stringResource(id = R.string.cancel),
            confirmationText = stringResource(id = R.string.apply),
        ) {
            FlowRow(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 33.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                maxItemsInEachRow = 4
            ) {
                choices.forEach { choice ->
                    FilterChip(
                        modifier = Modifier.padding(0.dp),
                        selected = choice == selected,
                        onClick = {
                            onSelectedChange(choice)
                        },
                        label = {
                            AnimatedContent(
                                targetState = if (choice == selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "FilterTitleColorAnime",
                            ) {
                                Text(
                                    text = choice,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.W500,
                                    color = it
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}