package com.ebbinghaus.review.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

object AppIcons {
    val AvailableIcons = mapOf(
        "Home" to Icons.Default.Home,
        "DateRange" to Icons.Default.DateRange,
        "Person" to Icons.Default.Person,
        "Star" to Icons.Default.Star,
        "Settings" to Icons.Default.Settings,
        "Menu" to Icons.Default.Menu,
        "Info" to Icons.Default.Info,
        "Favorite" to Icons.Default.Favorite,
        "Search" to Icons.Default.Search,
        "Edit" to Icons.Default.Edit,
        "List" to Icons.Default.List,
        "Notifications" to Icons.Default.Notifications,
        "CheckCircle" to Icons.Default.CheckCircle,
        "Face" to Icons.Default.Face,
        "AccountCircle" to Icons.Default.AccountCircle,
        "Home (Outlined)" to Icons.Outlined.Home,
        "DateRange (Outlined)" to Icons.Outlined.DateRange,
        "Person (Outlined)" to Icons.Outlined.Person,
        "Star (Outlined)" to Icons.Outlined.Star,
        "Settings (Outlined)" to Icons.Outlined.Settings,
        "Info (Outlined)" to Icons.Outlined.Info,
        "Favorite (Outlined)" to Icons.Outlined.Favorite,
        "Edit (Outlined)" to Icons.Outlined.Edit,
        "List (Outlined)" to Icons.Outlined.List,
        "Face (Outlined)" to Icons.Outlined.Face,
        "AccountCircle (Outlined)" to Icons.Outlined.AccountCircle
    )

    fun getIcon(name: String, default: ImageVector): ImageVector {
        return AvailableIcons.getOrDefault(name, default)
    }
}
