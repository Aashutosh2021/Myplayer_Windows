package com.example.myplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplayer.R
import com.example.myplayer.ui.navigation.Screen
import com.example.myplayer.ui.theme.*

data class NavItem(
    val route: String,
    val label: String,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector
)

val bottomNavItems = listOf(
    NavItem(Screen.Home.route,      "Home",      Icons.Filled.Home,         Icons.Outlined.Home),
    NavItem(Screen.Search.route,    "Search",    Icons.Filled.Search,       Icons.Outlined.Search),
    NavItem(Screen.Library.route,   "Library",   Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavItem(Screen.Trending.route,  "Hotlist",   Icons.Filled.Whatshot,     Icons.Outlined.Whatshot),
    NavItem(Screen.Settings.route,  "Settings",  Icons.Filled.Settings,     Icons.Outlined.Settings)
)

@Composable
fun FloatingNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(210.dp)
            .fillMaxHeight()
            .shadow(elevation = 6.dp, spotColor = CharcoalPrimary.copy(alpha = 0.08f))
            .background(PureWhite)
            .padding(horizontal = 14.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 8.dp, bottom = 28.dp, top = 4.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon_spark),
                contentDescription = "MyPlayer Logo",
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = "MYPLAYER",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = CharcoalPrimary
            )
        }

        bottomNavItems.forEach { item ->
            val isSelected = currentRoute == item.route
            val tint by animateColorAsState(
                targetValue = if (isSelected) CharcoalPrimary else SilverMuted,
                animationSpec = tween(180),
                label = "navTint_${item.route}"
            )
            val bg = if (isSelected) CloudBlueBackground else Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onNavigate(item.route) }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isSelected) item.activeIcon else item.inactiveIcon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = item.label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = tint
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (ThemeManager.isDark) Color(0xFF1F2532) else SoftGraySurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { ThemeManager.toggle() }
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (ThemeManager.isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Toggle Day / Night",
                tint = CharcoalPrimary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = if (ThemeManager.isDark) "Night Mode" else "Day Mode",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = CharcoalPrimary
            )
        }
    }
}