package io.github.windyx0.yamusiclsposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class PrefsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val prefs = context!!.getSharedPreferences("yamusic_prefs", Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("key", "value_string", "value_int", "value_boolean"))
        
        val key = uri.lastPathSegment ?: return cursor
        
        when (key) {
            "manual_token" -> cursor.addRow(arrayOf(key, prefs.getString(key, ""), 0, 0))
            "quality" -> cursor.addRow(arrayOf(key, "", prefs.getInt(key, 192), 0))
            "folder" -> cursor.addRow(arrayOf(key, prefs.getString(key, "Music"), 0, 0))
            "download_cover" -> cursor.addRow(arrayOf(key, "", 0, if (prefs.getBoolean(key, true)) 1 else 0))
            "cover_size" -> cursor.addRow(arrayOf(key, prefs.getString(key, "1000x1000"), 0, 0))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
