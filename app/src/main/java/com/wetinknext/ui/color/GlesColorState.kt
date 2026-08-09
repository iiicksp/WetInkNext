package com.wetinknext.ui.color

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject

class GlesColorState(context: Context? = null) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    val recentColors = mutableStateListOf<Color>()
    val userCollections = mutableStateListOf<ColorCollection>()
    var pinnedCollectionId by mutableLongStateOf(prefs?.getLong(PinnedCollectionKey, NoPinnedCollection) ?: NoPinnedCollection)
        private set
    var pinnedPaletteOffset by mutableStateOf(
        Offset(
            prefs?.getFloat(PinnedPaletteOffsetXKey, DefaultPinnedOffsetX) ?: DefaultPinnedOffsetX,
            prefs?.getFloat(PinnedPaletteOffsetYKey, DefaultPinnedOffsetY) ?: DefaultPinnedOffsetY,
        ),
    )
        private set

    init {
        loadRecentColors()
        loadUserCollections()
    }

    fun pushRecentColor(color: Color) {
        val argb = color.toArgb()
        val existingIndex = recentColors.indexOfFirst { it.toArgb() == argb }
        if (existingIndex == 0) return
        if (existingIndex > 0) recentColors.removeAt(existingIndex)
        recentColors.add(0, color)
        while (recentColors.size > MaxRecentColors) {
            recentColors.removeAt(recentColors.lastIndex)
        }
        saveRecentColors()
    }

    private fun loadRecentColors() {
        val encoded = prefs?.getString(RecentColorsKey, null) ?: return
        encoded.split(',')
            .mapNotNull { it.toIntOrNull() }
            .take(MaxRecentColors)
            .forEach { recentColors.add(Color(it)) }
    }

    private fun saveRecentColors() {
        prefs?.edit()
            ?.putString(RecentColorsKey, recentColors.joinToString(",") { it.toArgb().toString() })
            ?.apply()
    }

    fun addUserCollection(collection: ColorCollection) {
        userCollections.add(0, collection.copy(builtin = false))
        saveUserCollections()
    }

    fun deleteUserCollection(id: Long) {
        userCollections.removeAll { it.id == id }
        if (pinnedCollectionId == id) unpinCollection()
        saveUserCollections()
    }

    fun pinCollection(id: Long) {
        pinnedCollectionId = id
        prefs?.edit()?.putLong(PinnedCollectionKey, id)?.apply()
    }

    fun unpinCollection() {
        pinnedCollectionId = NoPinnedCollection
        prefs?.edit()?.remove(PinnedCollectionKey)?.apply()
    }

    fun persistPinnedPaletteOffset(offset: Offset) {
        pinnedPaletteOffset = offset
        prefs?.edit()
            ?.putFloat(PinnedPaletteOffsetXKey, offset.x)
            ?.putFloat(PinnedPaletteOffsetYKey, offset.y)
            ?.apply()
    }

    fun addColorToCollection(id: Long, color: Color) {
        updateUserCollection(id) { collection ->
            collection.copy(colors = collection.colors + color.toArgb())
        }
    }

    fun replaceColorInCollection(id: Long, slot: Int, color: Color) {
        updateUserCollection(id) { collection ->
            if (slot !in collection.colors.indices) return@updateUserCollection collection
            val colors = collection.colors.toMutableList()
            colors[slot] = color.toArgb()
            collection.copy(colors = colors)
        }
    }

    fun removeColorFromCollection(id: Long, slot: Int) {
        updateUserCollection(id) { collection ->
            if (slot !in collection.colors.indices) return@updateUserCollection collection
            val colors = collection.colors.toMutableList()
            colors.removeAt(slot)
            collection.copy(colors = colors)
        }
    }

    private fun updateUserCollection(id: Long, transform: (ColorCollection) -> ColorCollection) {
        val index = userCollections.indexOfFirst { it.id == id }
        if (index < 0) return
        userCollections[index] = transform(userCollections[index]).copy(builtin = false)
        saveUserCollections()
    }

    private fun loadUserCollections() {
        val encoded = prefs?.getString(UserCollectionsKey, null) ?: return
        runCatching {
            val array = JSONArray(encoded)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val colorsJson = item.optJSONArray("colors") ?: JSONArray()
                val colors = buildList {
                    for (j in 0 until colorsJson.length()) {
                        add(colorsJson.getInt(j))
                    }
                }
                userCollections.add(
                    ColorCollection(
                        id = item.optLong("id", System.currentTimeMillis() + i),
                        name = item.optString("name", "Новая палитра"),
                        colors = colors,
                        builtin = false,
                    ),
                )
            }
        }
    }

    private fun saveUserCollections() {
        val array = JSONArray()
        userCollections.filterNot { it.builtin }.forEach { collection ->
            val colors = JSONArray()
            collection.colors.forEach { colors.put(it) }
            array.put(
                JSONObject()
                    .put("id", collection.id)
                    .put("name", collection.name)
                    .put("colors", colors),
            )
        }
        prefs?.edit()
            ?.putString(UserCollectionsKey, array.toString())
            ?.apply()
    }

    private companion object {
        const val PrefsName = "wetinknext_color_state"
        const val RecentColorsKey = "recent_colors"
        const val UserCollectionsKey = "user_collections"
        const val PinnedCollectionKey = "pinned_collection"
        const val PinnedPaletteOffsetXKey = "pinned_palette_offset_x"
        const val PinnedPaletteOffsetYKey = "pinned_palette_offset_y"
        const val NoPinnedCollection = Long.MIN_VALUE
        const val DefaultPinnedOffsetX = 84f
        const val DefaultPinnedOffsetY = 128f
        const val MaxRecentColors = 14
    }
}
