package com.aichathub.app.util

import com.aichathub.app.data.local.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A labeled bucket of conversations for the history UI: conversations that
 * happened today sit under "Today", yesterday's under "Yesterday", and older
 * ones under their calendar date.
 */
data class ChatGroup(
    val label: String,
    val conversations: List<ConversationEntity>
)

object ConversationGroups {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Groups conversations (any order) into [ChatGroup]s sorted newest-first.
     * Input ordering is preserved within each group.
     */
    fun groupByDay(conversations: List<ConversationEntity>): List<ChatGroup> {
        val sorted = conversations.sortedByDescending { it.updatedAt }
        val now = Calendar.getInstance()
        val today = startOfDay(now).timeInMillis
        val yesterday = today - DAY_MS
        val currentYear = now.get(Calendar.YEAR)
        val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val yearFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        val groups = LinkedHashMap<String, MutableList<ConversationEntity>>()
        for (conv in sorted) {
            val label = when {
                conv.updatedAt >= today -> "Today"
                conv.updatedAt >= yesterday -> "Yesterday"
                else -> {
                    val cal = Calendar.getInstance().apply { timeInMillis = conv.updatedAt }
                    if (cal.get(Calendar.YEAR) == currentYear) {
                        dayFormat.format(Date(conv.updatedAt))
                    } else {
                        yearFormat.format(Date(conv.updatedAt))
                    }
                }
            }
            groups.getOrPut(label) { mutableListOf() }.add(conv)
        }
        return groups.map { ChatGroup(it.key, it.value) }
    }

    /** Short "HH:mm" label for a timestamp. */
    fun timeLabel(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun startOfDay(cal: Calendar): Calendar = (cal.clone() as Calendar).also {
        it.set(Calendar.HOUR_OF_DAY, 0)
        it.set(Calendar.MINUTE, 0)
        it.set(Calendar.SECOND, 0)
        it.set(Calendar.MILLISECOND, 0)
    }
}