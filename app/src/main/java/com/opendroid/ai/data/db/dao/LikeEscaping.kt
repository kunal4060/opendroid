package com.opendroid.ai.data.db.dao

/**
 * Escapes SQLite LIKE metacharacters so caller-supplied text is matched
 * literally. Only meaningful paired with `ESCAPE '\'` in the query itself:
 * without an escape clause, `%` and `_` in the pattern act as wildcards
 * (audit-4 #5/#6). The escape character itself is escaped first-class so a
 * literal backslash in the input can never swallow the character after it.
 */
object LikeEscaping {
    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch == '\\' || ch == '%' || ch == '_') append('\\')
            append(ch)
        }
    }
}
