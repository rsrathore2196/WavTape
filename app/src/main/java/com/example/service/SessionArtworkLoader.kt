package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.example.performance.AppDispatchers
import com.example.ui.util.providerArtworkUrl
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/** System media artwork uses the same recording's larger cover, independent of thumbnail sizes in the UI. */
@UnstableApi
internal class SessionArtworkLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    private val embedded = DataSourceBitmapLoader(DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
        DefaultDataSource.Factory(context), BitmapFactory.Options(), MAX_SIZE)

    override fun supportsMimeType(mimeType: String): Boolean = embedded.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = embedded.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        val load = scope.launch(AppDispatchers.Io) {
            try {
                val loader = Coil.imageLoader(context)
                for (candidate in artworkCandidates(uri)) {
                    val request = ImageRequest.Builder(context)
                        .data(candidate).size(MAX_SIZE, MAX_SIZE).scale(Scale.FIT).precision(Precision.INEXACT)
                        .allowHardware(false).build()
                    val loaded = loader.execute(request) as? SuccessResult
                    val bitmap = (loaded?.drawable as? BitmapDrawable)?.bitmap ?: continue
                    val largest = maxOf(bitmap.width, bitmap.height)
                    result.set(if (largest <= MAX_SIZE) bitmap else Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width.toLong() * MAX_SIZE / largest).toInt().coerceAtLeast(1),
                        (bitmap.height.toLong() * MAX_SIZE / largest).toInt().coerceAtLeast(1), true))
                    return@launch
                }
                throw IOException("Media artwork is unavailable")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                result.setException(failure)
            }
        }
        load.invokeOnCompletion { error -> if (error != null) result.setException(error) }
        result.addListener({ if (result.isCancelled) load.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    internal companion object {
        const val MAX_SIZE = 1024
        fun artworkCandidates(uri: Uri): List<Uri> {
            val source = uri.toString()
            val larger = providerArtworkUrl(source, MAX_SIZE)
            return if (larger == source) listOf(uri) else listOf(Uri.parse(larger), uri)
        }
    }
}
