package com.alondra1.app.domain

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/** A tag change that cannot be made, with a reason to show as it is. */
class TagException(message: String) : Exception(message)

/**
 * Tags (FEATURES_EXTENSION Part B §2), the same rules as the cloud (alondra1-backend internal/tasks/tags.go), Windows
 * and the Web app (contract/schemas/tags-v1, tag-links-v1). A tag is its own object; assigning it is a tag_link whose
 * id is derived from tag, object type and object, so two devices tagging the same item offline create the same record.
 */
object TagRules {
    const val MAX_NAME = 64
    const val MAX_TAGS = 500
    const val MAX_PER_ITEM = 50
    const val TASK = "task"
    const val NOTE = "note"
    const val EVENT = "event"
    const val MAIL = "mail"
    val COLORS = listOf("gray", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")

    /** The name as stored: trimmed and NFC. Throws [TagException] with a plain reason when it cannot be used. */
    fun cleanName(raw: String): String {
        val name = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        if (name.isEmpty()) throw TagException("Enter a name for the tag.")
        if (name.codePointCount(0, name.length) > MAX_NAME) throw TagException("Tag names can be at most $MAX_NAME characters.")
        if (name.any { Character.isISOControl(it) }) throw TagException("Tag names can't contain control characters.")
        return name
    }

    /** The uniqueness key: NFC and case-folded (with the full folds the cloud applies, such as ß → ss). */
    fun nameKey(name: String): String {
        val lower = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return Normalizer.normalize(lower.replace("ß", "ss").replace("ẞ", "ss").replace("ς", "σ"), Normalizer.Form.NFC)
    }

    /** 'tl_' + the first 40 characters of base64url(SHA-256(tagId U+001F objectType U+001F objectRef)). */
    fun linkId(tagId: String, objectType: String, objectRef: String): String {
        val sum = MessageDigest.getInstance("SHA-256").digest("$tagId\u001f$objectType\u001f$objectRef".toByteArray(Charsets.UTF_8))
        return "tl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(sum).take(40)
    }

    /** Usage in words for Settings and the delete prompt: "2 tasks, 1 note". */
    fun describeUsage(counts: Map<String, Int>): String {
        val words = listOf(Triple(TASK, "task", "tasks"), Triple(NOTE, "note", "notes"), Triple(EVENT, "event", "events"), Triple(MAIL, "message", "messages"))
        val parts = words.mapNotNull { (type, one, many) -> counts[type]?.takeIf { it > 0 }?.let { "$it ${if (it == 1) one else many}" } }
        return if (parts.isEmpty()) "Not used yet" else parts.joinToString(", ")
    }
}
