package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_update_calendar_event_description
import kai.composeapp.generated.resources.tool_update_calendar_event_name

object UpdateCalendarEventTool {

    const val ID = "update_calendar_event"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Update Calendar Event",
        description = "Edit an existing calendar event",
        nameRes = Res.string.tool_update_calendar_event_name,
        descriptionRes = Res.string.tool_update_calendar_event_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Edit an existing calendar event by id. Only the fields you provide are changed; " +
                "pass an empty string to clear a text field. For recurring events the change applies to the whole series. " +
                "Use list_calendar_events to find event ids.",
            parameters = mapOf(
                "event_id" to ParameterSchema("integer", "Id of the event to update (from list_calendar_events)", true),
                "title" to ParameterSchema("string", "New title", false),
                "start_time" to ParameterSchema("string", "New start time as ISO 8601, e.g. '2024-03-15T14:30:00+02:00'", false),
                "end_time" to ParameterSchema("string", "New end time as ISO 8601", false),
                "description" to ParameterSchema("string", "New description (empty string clears it)", false),
                "location" to ParameterSchema("string", "New location (empty string clears it)", false),
                "reminder_minutes" to ParameterSchema("integer", "Replace the reminder: minutes before the event (0 removes reminders)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val eventId = (args["event_id"] as? Number)?.toLong()
                ?: return mapOf("success" to false, "error" to "event_id is required")
            val title = args["title"] as? String
            val startTime = args["start_time"] as? String
            val endTime = args["end_time"] as? String
            val description = args["description"] as? String
            val location = args["location"] as? String
            val reminderMinutes = (args["reminder_minutes"] as? Number)?.toInt()

            return when (
                val result = calendarRepository.updateEvent(
                    eventId = eventId,
                    title = title,
                    startTimeIso = startTime,
                    endTimeIso = endTime,
                    description = description,
                    location = location,
                    reminderMinutes = reminderMinutes,
                )
            ) {
                is CalendarResult.Success -> mapOf(
                    "success" to true,
                    "event_id" to result.eventId,
                    "title" to result.title,
                    "message" to "Event '${result.title}' updated successfully",
                )

                is CalendarResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )
            }
        }
    }
}
