/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

/**
 * A container of optional features of the [org.openani.mediamp.MediampPlayer].
 * @see Feature
 */
public sealed interface PlayerFeatures {
    /**
     * Obtains the feature instance associated with the given [key],
     * or `null` if the feature is not available.
     */
    public operator fun <F : Feature> get(key: FeatureKey<F>): F?

    /**
     * Obtains the feature instance associated with the given [key],
     * or throws [UnsupportedFeatureException] if the feature is not available.
     */
    public fun <F : Feature> getOrFail(key: FeatureKey<F>): F {
        return get(key) ?: throw UnsupportedFeatureException(key)
    }

    /**
     * Checks if the player supports the feature associated with the given [key].
     * @return `true` if the feature is available, `false` otherwise.
     */
    public fun supports(key: FeatureKey<*>): Boolean = get(key) != null
}


public fun playerFeaturesOf(vararg features: Pair<FeatureKey<*>, Feature>): PlayerFeatures {
    val map = features.toMap()
    return MapPlayerFeatures(map)
}

public fun playerFeaturesOf(features: Map<FeatureKey<*>, Feature>): PlayerFeatures {
    val map = features.toMap() // copy
    return MapPlayerFeatures(map)
}

public inline fun buildPlayerFeatures(builder: PlayerFeaturesBuilder.() -> Unit): PlayerFeatures {
    return PlayerFeaturesBuilder().apply(builder).build()
}

public class PlayerFeaturesBuilder @PublishedApi internal constructor() {
    private val features = mutableMapOf<FeatureKey<*>, Feature>()

    public fun <F : Feature> add(key: FeatureKey<F>, feature: F) {
        features[key] = feature
    }

    public fun build(): PlayerFeatures = playerFeaturesOf(features)
}


public class UnsupportedFeatureException(key: FeatureKey<*>) :
    UnsupportedOperationException("Feature $key is not supported")


private class MapPlayerFeatures(
    private val map: Map<FeatureKey<*>, Feature>
) : PlayerFeatures {
    override fun <F : Feature> get(key: FeatureKey<F>): F? {
        @Suppress("UNCHECKED_CAST")
        return map[key] as F?
    }
}
