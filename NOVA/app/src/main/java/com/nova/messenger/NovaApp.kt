package com.nova.messenger

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.nova.messenger.data.Graph
import com.nova.messenger.data.Notifier

class NovaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Graph.app = this
        Notifier.init(this)
    }
    // GIF-аватары (анимированные аватары — функция Premium)
    override fun newImageLoader() = ImageLoader.Builder(this).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
}
