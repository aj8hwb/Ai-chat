package com.aichathub.app.util

import com.aichathub.app.data.local.ConversationEntity
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationGroupsTest {

    private fun conv(updatedAt: Long, title: String = "t") = ConversationEntity(
        id = 0,
        title = title,
        modelId = "m",
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun daysAgo(days: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `groups by today yesterday and older`() {
        val groups = ConversationGroups.groupByDay(
            listOf(conv(daysAgo(2)), conv(daysAgo(1)), conv(daysAgo(0)))
        )
        assertEquals(3, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals("Yesterday", groups[1].label)
        assert(groups[2].label != "Today")
        assert(groups[2].label != "Yesterday")
    }

    @Test
    fun `groups are sorted newest first`() {
        val groups = ConversationGroups.groupByDay(
            listOf(conv(daysAgo(5)), conv(daysAgo(0)))
        )
        assertEquals("Today", groups.first().label)
        assertEquals(1, groups.first().conversations.size)
    }

    @Test
    fun `older conversations get a date label not today`() {
        val groups = ConversationGroups.groupByDay(listOf(conv(daysAgo(10))))
        assert(groups.single().label != "Today")
        assert(groups.single().label != "Yesterday")
    }

    @Test
    fun `time label is HH colon MM`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 5)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val label = ConversationGroups.timeLabel(cal.timeInMillis)
        assertEquals("09:05", label)
    }
}