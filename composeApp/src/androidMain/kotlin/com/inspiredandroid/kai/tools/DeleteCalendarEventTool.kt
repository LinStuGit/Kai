package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_delete_calendar_event_description
import kai.composeapp.generated.resources.tool_delete_calendar_event_name

object DeleteCalendarEventTool {

    const val ID = "delete_calendar_event"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Delete Calendar Event",
        description = "Delete a calendar event",
        nameRes = Res.string.tool_delete_calendar_event_name,
        descriptionRes = Res.string.tool_delete_calendar_event_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Delete a calendar event by id. Deleting a recurring event removes the whole series. " +
                "Use list_calendar_events to find event ids.",
            parameters = mapOf(
                "event_id" to ParameterSchema("integer", "Id of the event to delete (from list_calendar_events)", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val eventId = (args["event_id"] as? Number)?.toLong()
                ?: return mapOf("success" to false, "error" to "event_id is required")

            return when (val result = calendarRepository.deleteEvent(eventId)) {
                is CalendarResult.Success -> mapOf(
                    "success" to true,
                    "event_id" to result.eventId,
                    "message" to "Event '${result.title}' deleted successfully",
                )

                is CalendarResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )
            }
        }
    }
}
