package com.deanwaylabs.deanwave.library

import android.content.Context
import android.provider.MediaStore
import java.io.File

class LocalLibraryScanner(private val context: Context) {

    // Scans the device storage drive for matching audio files
    fun scanLocalDrive(): List<File> {
        val audioFiles = mutableListOf<File>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val filePath = cursor.getString(dataIndex)
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists() && file.isFile) {
                        audioFiles.add(file)
                    }
                }
            }
        }
        return audioFiles
    }
}
