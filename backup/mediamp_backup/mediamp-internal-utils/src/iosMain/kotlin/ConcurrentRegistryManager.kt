/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.internal

import kotlin.concurrent.AtomicReference

class ConcurrentRegistryManager<T> : Iterable<T> {
    private val head = AtomicReference<Node<T>?>(null)

    fun append(item: T) {
        while (true) {
            val current = head.value
            val new = Node(item, current)

            if (head.compareAndSet(current, new)) break
        }
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var current = head.value

        override fun next(): T {
            val result = current!!
            current = result.next
            return result.item
        }

        override fun hasNext(): Boolean = (null != current)
    }

    private class Node<T>(
        val item: T,
        val next: Node<T>?
    )
}
