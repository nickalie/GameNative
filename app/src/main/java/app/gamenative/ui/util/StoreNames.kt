package app.gamenative.ui.util

import androidx.annotation.StringRes
import app.gamenative.R
import app.gamenative.data.GameSource

/** The store's name as Settings → Stores and store-related messages show it. */
@get:StringRes
val GameSource.storeNameRes: Int
    get() = when (this) {
        GameSource.STEAM -> R.string.tab_steam
        GameSource.CUSTOM_GAME -> R.string.custom_games_title
        GameSource.GOG -> R.string.tab_gog
        GameSource.EPIC -> R.string.tab_epic
        GameSource.AMAZON -> R.string.tab_amazon
    }
