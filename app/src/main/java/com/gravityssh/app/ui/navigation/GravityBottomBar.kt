package com.gravityssh.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gravityssh.app.ui.components.AgIcons
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgSurface

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    NavTab(Routes.SERVERS, "主机", AgIcons.Dns),
    NavTab(Routes.TERMINAL, "终端", AgIcons.Terminal),
    NavTab(Routes.KEYS, "密钥", AgIcons.Key),
    NavTab(Routes.TOOLS, "效率", AgIcons.Bolt),
    NavTab(Routes.SETTINGS, "设置", Icons.Rounded.Settings)
)

@Composable
fun GravityBottomBar(
    currentRoute: String,
    activeSessionCount: Int,
    onSelect: (String) -> Unit
) {
    NavigationBar(containerColor = AgSurface, tonalElevation = 0.dp) {
        BOTTOM_TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                icon = {
                    if (tab.route == Routes.TERMINAL && activeSessionCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = AgAccent, contentColor = Color.White) {
                                    Text(activeSessionCount.toString())
                                }
                            }
                        ) { Icon(tab.icon, contentDescription = tab.label) }
                    } else {
                        Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AgAccent,
                    selectedTextColor = AgAccent,
                    unselectedIconColor = AgMuted,
                    unselectedTextColor = AgMuted,
                    indicatorColor = AgAccent.copy(alpha = 0.14f)
                )
            )
        }
    }
}
