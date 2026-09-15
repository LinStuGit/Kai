package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.Platform
import com.inspiredandroid.kai.currentPlatform
import com.inspiredandroid.kai.mcp.PopularMcpServer
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.skills.RegistrySkillEntry
import com.inspiredandroid.kai.skills.SkillManifest
import com.inspiredandroid.kai.tools.AgentExtension
import com.inspiredandroid.kai.tools.getAgentExtensions
import com.inspiredandroid.kai.tools.removeAgentExtension
import com.inspiredandroid.kai.tools.setAgentExtensionEnabled
import com.inspiredandroid.kai.ui.handCursor
import com.inspiredandroid.kai.ui.kaiAdaptiveCardBorder
import com.inspiredandroid.kai.ui.kaiAdaptiveCardColors
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.settings_tools_description
import kai.composeapp.generated.resources.settings_tools_none_available
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ToolsContent(
    tools: ImmutableList<ToolInfo>,
    onToggleTool: (String, Boolean) -> Unit,
    mcpServers: ImmutableList<McpServerUiState>,
    onAddMcpServer: (String, String, Map<String, String>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    showAddMcpServerDialog: Boolean,
    onShowAddMcpServerDialog: (Boolean) -> Unit,
    onAddPopularMcpServer: (PopularMcpServer) -> Unit,
    skills: ImmutableList<SkillManifest>,
    onUninstallSkill: (String) -> Unit,
    showAddSkillDialog: Boolean,
    onShowAddSkillDialog: (Boolean) -> Unit,
    onInstallGitHubSkill: (String) -> Unit,
    onInstallBrowsedSkill: (RegistrySkillEntry) -> Unit,
    isInstallingSkill: Boolean,
    skillInstallError: String?,
    browsableSkills: ImmutableList<RegistrySkillEntry>,
    isBrowsingSkills: Boolean,
    browseSkillsFailed: Boolean,
    showSkills: Boolean,
    isSandboxInstalled: Boolean,
    onNavigateToSandbox: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // MCP Servers section
        McpServersSection(
            mcpServers = mcpServers,
            onAddMcpServer = onAddMcpServer,
            onRemoveMcpServer = onRemoveMcpServer,
            onToggleMcpServer = onToggleMcpServer,
            onRefreshMcpServer = onRefreshMcpServer,
            onToggleTool = onToggleTool,
            showAddDialog = showAddMcpServerDialog,
            onShowAddDialog = onShowAddMcpServerDialog,
            onAddPopularMcpServer = onAddPopularMcpServer,
        )

        // Skills section — sandbox-backed, so Android only.
        if (showSkills) {
            Spacer(Modifier.height(24.dp))
            SkillsSection(
                skills = skills,
                onUninstallSkill = onUninstallSkill,
                showAddDialog = showAddSkillDialog,
                onShowAddDialog = onShowAddSkillDialog,
                onInstallGitHub = onInstallGitHubSkill,
                onInstallBrowsed = onInstallBrowsedSkill,
                isInstalling = isInstallingSkill,
                installError = skillInstallError,
                browsableSkills = browsableSkills,
                isBrowsing = isBrowsingSkills,
                browseFailed = browseSkillsFailed,
                isSandboxInstalled = isSandboxInstalled,
                onNavigateToSandbox = onNavigateToSandbox,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Native tools section
        Text(
            text = stringResource(Res.string.settings_tools_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        if (tools.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_tools_none_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 800.dp -> 3
                    maxWidth >= 500.dp -> 2
                    else -> 1
                }
                val rows = tools.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTools.forEach { tool ->
                                ToolItem(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    tool = tool,
                                    onToggle = { enabled -> onToggleTool(tool.id, enabled) },
                                )
                            }
                            // Fill empty slots so last row items don't stretch
                            repeat(columns - rowTools.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Agent-registered extensions (Shizuku-backed, Android only)
        ExtensionsSection()
    }
}

/**
 * Agent extensions registered via add_extension, each toggleable/removable
 * here. Small list read straight from the platform registry on every
 * interaction, bumped by a revision counter — no ViewModel round-trip.
 */
@Composable
private fun ExtensionsSection() {
    if (currentPlatform !is Platform.Mobile.Android) return

    var revision by remember { mutableIntStateOf(0) }
    val extensions = remember(revision) { getAgentExtensions() }

    Spacer(Modifier.height(24.dp))

    Text(
        text = "Agent 拓展",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "agent 通过 add_extension 固化的宿主机命令，可在此启停或删除",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    if (extensions.isEmpty()) {
        Text(
            text = "还没有 agent 注册的拓展——在对话里让它用 add_extension 添加",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        extensions.forEach { extension ->
            ExtensionItem(
                extension = extension,
                onToggle = { enabled ->
                    setAgentExtensionEnabled(extension.id, enabled)
                    revision++
                },
                onRemove = {
                    removeAgentExtension(extension.id)
                    revision++
                },
            )
        }
    }
}

@Composable
private fun ExtensionItem(
    extension: AgentExtension,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (extension.desc.isNotBlank()) {
                    Text(
                        text = extension.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = extension.cmd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(16.dp))

            Switch(
                checked = extension.enabled,
                onCheckedChange = onToggle,
            )

            TextButton(onClick = onRemove) {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ToolItem(
    tool: ToolInfo,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clip(CardDefaults.shape)
            .clickable { onToggle(!tool.isEnabled) }
            .handCursor(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.nameRes?.let { stringResource(it) } ?: tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = tool.descriptionRes?.let { stringResource(it) } ?: tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(16.dp))

            Switch(
                checked = tool.isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
