package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_list_calendar_events_description
import kai.composeapp.generated.resources.tool_list_calendar_events_name

object ListCalendarEventsTool {

    const val ID = "list_calendar_events"

    val toolInfo = ToolInfo(
        id = ID,
        name = "List Calendar Events",
        description = "List the user's upcoming calendar events",
        nameRes = Res.string.tool_list_calendar_events_name,
        descriptionRes = Res.string.tool_list_calendar_events_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "List the user's upcoming calendar events, sorted by start time. " +
                "Recurring events appear as individual upcoming occurrences.",
            parameters = mapOf(
                "days" to ParameterSchema("integer", "How many days ahead to look, starting now (default: 7, max: 60)", false),
                "query" to ParameterSchema("string", "Only return events whose title or description contains this text", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val days = (args["days"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 7
            val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            val now = System.currentTimeMillis()
            return when (
                val result = calendarRepository.listEvents(
                    startMs = now,
                    endMs = now + days.toLong() * 24 * 60 * 60 * 1000,
                    query = query,
                )
            ) {
                is EventListResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )

                is EventListResult.Ok -> mapOf(
                    "success" to true,
                    "count" to result.events.size,
                    "events" to result.events.map {
                        mapOf(
                            "event_id" to it.eventId,
                            "title" to it.title,
                            "start" to it.startIso,
                            "end" to it.endIso,
                            "all_day" to it.allDay,
                            "location" to (it.location ?: ""),
                            "description" to (it.description ?: ""),
                            "reminder_minutes_before" to (it.reminderMinutes ?: 0),
                        )
                    },
                    "message" to if (result.events.isEmpty()) {
                        "No upcoming events in the next $days day(s)"
                    } else {
                        "Found ${result.events.size} event(s) in the next $days day(s)"
                    },
                )
            }
        }
    }
}
