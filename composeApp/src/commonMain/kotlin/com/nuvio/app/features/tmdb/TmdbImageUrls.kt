package com.nuvio.app.features.tmdb

private const val TMDB_W1280_IMAGE_PREFIX = "https://image.tmdb.org/t/p/w1280/"
private const val TMDB_ORIGINAL_IMAGE_PREFIX = "https://image.tmdb.org/t/p/original/"

internal fun originalTmdbImageUrl(imageUrl: String?): String? {
    if (imageUrl == null || !imageUrl.startsWith(TMDB_W1280_IMAGE_PREFIX)) return imageUrl

    return TMDB_ORIGINAL_IMAGE_PREFIX + imageUrl.removePrefix(TMDB_W1280_IMAGE_PREFIX)
}
