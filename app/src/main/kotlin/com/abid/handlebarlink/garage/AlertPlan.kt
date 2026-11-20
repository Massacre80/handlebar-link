package com.abid.handlebarlink.garage

// Which reminders get a notification from the daily check (docs/PLAN.md "Garage"). Each reminder is notified at most
// once per stage for the same due date or km: when it becomes due soon, a week before for a paper reminded earlier
// ("On 14 Feb, and again a week before"), and when it is overdue. A new date or km (a renewed paper, a logged service)
// is a new target and starts again. A check that skipped a stage (the phone was off) notifies only the latest one.

/** What the check notifies now and the marks to store afterwards. */
data class AlertPlan(
    val notify: List<Pair<Due, AlertStage>>,
    val marks: Map<String, AlertMark>,
)

/** The stage [due] has reached today; null when it needs no notification. */
fun alertStage(due: Due): AlertStage? =
    when {
        due.state == DueState.OVERDUE -> AlertStage.OVERDUE
        due.state != DueState.SOON -> null
        due.leadDays != null && due.leadDays > WEEK && due.daysLeft != null && due.daysLeft <= WEEK -> AlertStage.WEEK
        else -> AlertStage.SOON
    }

/** The due date or km [due] is counted to: a change means a new target. */
fun alertTarget(due: Due): String = "${due.dueDay ?: "-"}/${due.dueKm ?: "-"}"

/**
 * Decides the notifications for [dues] given what was already notified ([marks]). Marks of reminders that no longer
 * need one (done, removed, back to OK) are dropped, so the next time they come due they are notified again.
 */
fun planAlerts(
    dues: List<Due>,
    marks: Map<String, AlertMark>,
): AlertPlan {
    val notify = mutableListOf<Pair<Due, AlertStage>>()
    val next = mutableMapOf<String, AlertMark>()
    dues.forEach { due ->
        val stage = alertStage(due) ?: return@forEach
        val target = alertTarget(due)
        val mark = marks[due.id]?.takeIf { it.target == target }
        if (mark == null || mark.stage < stage) notify += due to stage
        next[due.id] = AlertMark(target, maxOf(stage, mark?.stage ?: stage))
    }
    return AlertPlan(notify, next)
}

/** All of today's reminders that can come due, the service first. */
fun GarageStatus.allDues(): List<Due> = listOfNotNull(service) + upcoming

private const val WEEK = 7
