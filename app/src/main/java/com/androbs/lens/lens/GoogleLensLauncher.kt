package com.androbs.lens.lens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.androbs.lens.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object GoogleLensLauncher {
    suspend fun launch(context: Context, bitmap: Bitmap) {
        try {
            val uri = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "images").apply { mkdirs() }
                val image = File(directory, "lens_capture.png")
                FileOutputStream(image).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage("com.google.ar.lens")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    context.getString(R.string.open_google_lens)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(chooser)
            }
        } catch (error: Exception) {
            Log.e("Lente", "Error launching Google Lens: ${error.message}", error)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.error_google_lens, error.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
