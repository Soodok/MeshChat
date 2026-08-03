package com.meshchat.app.mesh.identity

import kotlin.random.Random

object ShortIdGen {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(length: Int = 4, random: Random = Random): String =
        (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}

class LocalIdentity(
    val shortId: String = ShortIdGen.generate(),
    var displayName: String = "节点$shortId",
)
