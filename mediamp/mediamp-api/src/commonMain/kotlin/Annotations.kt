/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

// documentation copied from kotlinx-coroutines

/**
 * Marks declarations that are still **experimental** in mediamp API, which means that the design of the
 * corresponding declarations has open issues which may (or may not) lead to their changes in the future.
 * Roughly speaking, there is a chance that those declarations will be deprecated in the near future or
 * the semantics of their behavior may change in some way that may break some code.
 */
@RequiresOptIn(
    "This API is experimental and may be changed with short notice in the future.",
    RequiresOptIn.Level.WARNING,
)
public annotation class ExperimentalMediampApi

@RequiresOptIn(
    message = "This is a mediamp API that is not intended to be inherited from, " +
            "as the library may handle predefined instances of this in a special manner. " +
            "This will be an error in a future release. " +
            "If you need to inherit from this, please describe your use case in " +
            "https://github.com/open-ani/mediamp/issues, so that we can provide a stable API for inheritance. ",
    level = RequiresOptIn.Level.ERROR,
)
public annotation class InternalForInheritanceMediampApi

/**
 * Marks the annotated element as internal API of Mediamp.
 *
 * An internal API is subject to change without any notice and should not be used by you.
 */
@RequiresOptIn(
    message = "This is an internal mediamp API that should not be used by you. " +
            "It is subject to change without any notice. ",
    level = RequiresOptIn.Level.ERROR,
)
public annotation class InternalMediampApi
