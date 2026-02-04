package com.example

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.google.common.collect.ImmutableList

fun main() = runBlocking {
    println("Hello from simple-gradle-project!")
    delay(100)

    val items = ImmutableList.of("dependency", "manager", "test")
    items.forEach { println(it) }
}
