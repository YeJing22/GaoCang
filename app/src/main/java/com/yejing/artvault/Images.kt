package com.yejing.artvault

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder

/**
 * 全局图片加载器。
 *
 * Coil 2.x 的 GIF 解码是可选组件，不显式注册 `GifDecoder` 就只解第一帧——
 * 库里 `.gif` 的稿在详情页是静止的，原因在这里，不在入库环节（`Import` 早就认 gif）。
 *
 * 只注册解码器，不动磁盘缓存策略：网格吃的是 `thumbs/` 的静态 webp（首帧），
 * 只有详情页取原图时才会走动图解码，所以 1813 件不会同时解动图。
 */
fun appImageLoader(ctx: Context): ImageLoader =
    ImageLoader.Builder(ctx)
        .components { add(GifDecoder.Factory()) }
        .build()
