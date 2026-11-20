package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.GurivaDatabase
import com.example.data.repository.GurivaLocalRepository
import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.DialogueTurn
import com.example.domain.model.NormalizedRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("GURIVA", appName)
  }

  @Test
  fun `boardEventJsonRoundTrip preserves pageIndex and title`() {
    val event = BoardEvent(
      eventId = "evt_p2",
      lessonId = "lesson_1",
      stepId = "step_2",
      sequence = 1,
      type = BoardEventType.WRITE_MATH,
      payload = "x = 5",
      viewport = NormalizedRect(0.06f, 0.08f, 0.85f, 0.09f),
      status = BoardEventStatus.COMPLETE,
      pageIndex = 2,
      pageTitle = "Linear Equation"
    )
    val json = event.toJson()
    val restored = BoardEvent.fromJson(json, "lesson_1")
    assertEquals(2, restored?.pageIndex)
    assertEquals("Linear Equation", restored?.pageTitle)
    assertEquals("x = 5", restored?.payload)
  }

  @Test
  fun `localRepository persists unlimited lessons and events without caps`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, GurivaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = GurivaLocalRepository(db.gurivaDao())
      val manyEvents = (1..150).map { idx ->
        BoardEvent(
          eventId = "evt_$idx",
          lessonId = "lesson_unlim",
          stepId = "step_1",
          sequence = idx,
          type = BoardEventType.WRITE_TEXT,
          payload = "Line $idx",
          viewport = NormalizedRect(0.08f, 0.1f, 0.8f, 0.08f),
          status = BoardEventStatus.COMPLETE,
          pageIndex = idx / 20
        )
      }
      val manyTurns = (1..60).map { idx ->
        DialogueTurn(role = if (idx % 2 == 0) "teacher" else "student", text = "Turn $idx")
      }

      val id = repo.createOrSaveLesson(
        existingLessonId = "lesson_unlim",
        title = "Unlimited Algebra",
        subject = "Maths",
        level = "Intermediate",
        language = "Auto",
        status = "active",
        summary = "Full lesson",
        currentStep = "Step 150",
        formulas = listOf("a^2 + b^2 = c^2"),
        unresolvedQuestions = emptyList(),
        boardEvents = manyEvents,
        recentTurns = manyTurns
      ).getOrThrow()

      val loaded = repo.getLesson(id).getOrNull()
      assertNotNull(loaded)
      assertEquals(150, loaded?.boardEvents?.size)
      assertEquals(60, loaded?.recentTurns?.size)
    } finally {
      db.close()
    }
  }
}
