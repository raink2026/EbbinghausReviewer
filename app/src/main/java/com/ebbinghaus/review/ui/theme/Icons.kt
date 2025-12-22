package com.ebbinghaus.review.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

object AppIcons {
    val AvailableIcons: Map<String, ImageVector> = mapOf(
        // Default
        "Home" to Icons.Filled.Home,
        "DateRange" to Icons.Filled.DateRange,
        "Person" to Icons.Filled.Person,

        // Filled Alternates
        "Star" to Icons.Filled.Star,
        "Settings" to Icons.Filled.Settings,
        "Menu" to Icons.Filled.Menu,
        "Info" to Icons.Filled.Info,
        "Favorite" to Icons.Filled.Favorite,
        "Search" to Icons.Filled.Search,
        "Edit" to Icons.Filled.Edit,
        "List" to Icons.Filled.List,
        "Notifications" to Icons.Filled.Notifications,
        "CheckCircle" to Icons.Filled.CheckCircle,
        "Face" to Icons.Filled.Face,
        "AccountCircle" to Icons.Filled.AccountCircle,

        // Outlined
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
        return AvailableIcons[name] ?: default
    }
}
