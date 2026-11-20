package com.example.core.navigation

object GurivaRoutes {
    const val ROUTE_HOME = "home"
    const val ROUTE_CLASSROOM = "classroom"
    const val ROUTE_CLASSROOM_PATTERN = "classroom?lessonId={lessonId}&subject={subject}&level={level}"
    const val ROUTE_LESSONS = "lessons"
    const val ROUTE_PROGRESS = "progress"
    const val ROUTE_SETTINGS = "settings"

    fun classroomRoute(
        lessonId: String? = null,
        subject: String = "Maths",
        level: String = "Beginner"
    ): String {
        val params = buildList {
            if (!lessonId.isNullOrBlank()) add("lessonId=$lessonId")
            add("subject=$subject")
            add("level=$level")
        }.joinToString("&")
        return "$ROUTE_CLASSROOM?$params"
    }
}
