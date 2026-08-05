package com.meshchat.app.mesh.protocol

/**
 * Single source of truth for the current on-air payload security contract.
 *
 * The existing MeshEnvelope format contains serialised bodies directly, so it is deliberately
 * marked as plaintext until an interoperable, reviewed end-to-end protocol replaces it. This
 * constant prevents UI or release checks from accidentally claiming protection that does not
 * exist. Do not flip it without changing the actual encoder, decoder, key agreement, replay
 * protection, migration path, and tests together.
 */
object MeshTransportSecurity {
    const val CURRENT_MODE: String = "legacy-plaintext-v1"
    const val END_TO_END_ENCRYPTION_ENFORCED: Boolean = false
}
