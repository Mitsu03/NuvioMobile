package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * O Coil só constrói uma cache de disco por omissão no Android. Nas restantes
 * plataformas `diskCachePolicy(ENABLED)` não chega: sem um `diskCache` explícito
 * não existe cache nenhuma, e cada imagem despejada da memória volta a ser
 * descarregada da rede — o que se sente a percorrer as prateleiras para cima e
 * para baixo, à medida que os pósteres mais antigos vão saindo da memória.
 */
internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    diskCache {
        DiskCache.Builder()
            .directory(cachesDirectory().toPath() / "coil_image_cache")
            .maxSizeBytes(512L * 1024 * 1024)
            .build()
    }

private fun cachesDirectory(): String =
    NSSearchPathForDirectoriesInDomains(
        directory = NSCachesDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).first() as String
