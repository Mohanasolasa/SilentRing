package com.example.autosilent.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.SoundMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ComposeUiIntegrationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: AutoSilentApplication

    private fun cleanDatabase() = runBlocking {
        app.repository.getSchedulesFlow().first().forEach {
            app.repository.deleteSchedule(it.id)
        }
        val nowInstant = Instant.now()
        app.repository.getActiveOverrides(nowInstant).forEach {
            app.repository.deleteOverride(it.scheduleId, it.occurrenceStart)
        }
        app.reconciler.reconcile(nowInstant, ZoneId.systemDefault())
    }

    @Before
    fun setUp() {
        app = composeTestRule.activity.application as AutoSilentApplication
        cleanDatabase()
    }

    @After
    fun tearDown() {
        cleanDatabase()
    }

    // 1. Home renders
    @Test
    fun home_rendersCorrectly() {
        composeTestRule.onNodeWithText("SilentRing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Phone is Ringing").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add Schedule").assertIsDisplayed()
    }

    // 2. Add Schedule navigation
    @Test
    fun addScheduleNavigation_opensCreateForm() {
        composeTestRule.onNodeWithContentDescription("Add Schedule").performClick()
        composeTestRule.onNodeWithContentDescription("Schedule Name Input").assertIsDisplayed()
    }

    // 3. Create validation - empty name error
    @Test
    fun createValidation_showsErrorOnEmptyName() {
        composeTestRule.onNodeWithContentDescription("Add Schedule").performClick()
        composeTestRule.onNodeWithContentDescription("Save Schedule Button").performClick()
        composeTestRule.onNodeWithText("Schedule name cannot be empty.").assertIsDisplayed()
    }

    // 4. Create schedule - adds item to Home
    @Test
    fun createSchedule_addsItemToHomeList() {
        composeTestRule.onNodeWithContentDescription("Add Schedule").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Schedule Name Input").performTextInput("Study Session")
        composeTestRule.onNodeWithContentDescription("TopBar Save Schedule").performClick()
        composeTestRule.waitForIdle()

        // Returns to Home and shows created schedule
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Study Session").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Study Session").assertIsDisplayed()
    }

    // 5. Edit schedule
    @Test
    fun editSchedule_preloadsAndUpdates() {
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val nowZdt = nowInstant.atZone(zone)
        runBlocking {
            app.repository.insertSchedule(
                Schedule(
                    name = "Work Focus",
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(17, 0),
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = nowZdt.toLocalDate()
                )
            )
        }

        // Open details
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Work Focus").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Work Focus").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Schedule Details").assertIsDisplayed()

        // Click Edit
        composeTestRule.onNodeWithContentDescription("Edit Schedule").performClick()
        composeTestRule.waitForIdle()

        // Modify name
        composeTestRule.onNodeWithContentDescription("Schedule Name Input").performTextReplacement("Work Focus Edited")
        composeTestRule.onNodeWithContentDescription("TopBar Save Schedule").performClick()
        composeTestRule.waitForIdle()

        // Check updated in details
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Work Focus Edited").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Work Focus Edited").assertIsDisplayed()
    }

    // 6. Delete confirmation
    @Test
    fun deleteConfirmation_deletesSchedule() {
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val nowZdt = nowInstant.atZone(zone)
        runBlocking {
            app.repository.insertSchedule(
                Schedule(
                    name = "Temporary Task",
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(9, 0),
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = nowZdt.toLocalDate()
                )
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Temporary Task").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Temporary Task").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Delete Schedule").performClick()
        composeTestRule.waitForIdle()

        // Verify confirmation dialog
        composeTestRule.onNodeWithText("Delete Temporary Task?").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Confirm Delete Schedule").performClick()
        composeTestRule.waitForIdle()

        // Wait until navigation back to Home completes and item disappears
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Temporary Task").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("SilentRing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Temporary Task").assertDoesNotExist()

        // Wait until Room repository emits empty state
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.repository.getSchedulesFlow().first().isEmpty() }
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Schedules (0)"))
        composeTestRule.onNodeWithText("Schedules (0)").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("No Schedules Yet"))
        composeTestRule.onNodeWithText("No Schedules Yet").assertIsDisplayed()
    }

    // 7. Enable / Disable toggle
    @Test
    fun enableDisable_togglesScheduleState() {
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val nowZdt = nowInstant.atZone(zone)
        runBlocking {
            app.repository.insertSchedule(
                Schedule(
                    name = "Toggle Item",
                    enabled = true,
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(9, 0),
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = nowZdt.toLocalDate()
                )
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription("Toggle Toggle Item").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Toggle Toggle Item").performClick()

        // Check Room state updated to disabled
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.repository.getEnabledSchedules().isEmpty() }
        }
        runBlocking {
            val list = app.repository.getEnabledSchedules()
            assert(list.isEmpty())
        }
    }

    // 8. Manual override / Resume Schedule
    @Test
    fun manualOverride_showsResumeScheduleAndClearsOverride() {
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val startInstant = nowInstant.minus(java.time.Duration.ofMinutes(10))
        val endInstant = nowInstant.plus(java.time.Duration.ofMinutes(50))
        val startZdt = startInstant.atZone(zone)
        val endZdt = endInstant.atZone(zone)

        runBlocking {
            val schedId = app.repository.insertSchedule(
                Schedule(
                    name = "Active Focus",
                    startTime = startZdt.toLocalTime(),
                    endTime = endZdt.toLocalTime(),
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = startZdt.toLocalDate()
                )
            )
            val eval = app.reconciler.reconcile(nowInstant, zone)
            assert(eval.activeOccurrences.isNotEmpty()) { "Schedule must be active at nowInstant" }
            val occ = eval.activeOccurrences.first()
            app.repository.recordOccurrenceOverride(
                OccurrenceOverride(
                    scheduleId = schedId,
                    occurrenceStart = occ.startInstant,
                    occurrenceEnd = occ.endInstant,
                    createdAt = nowInstant
                )
            )
            app.reconciler.reconcile(nowInstant.plusSeconds(5), zone)
        }

        // Home shows Overridden badge and Resume Schedule button
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Resume Schedule (Cancel Override)").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Resume Schedule (Cancel Override)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Resume Schedule (Cancel Override)").performClick()

        // Override cleared
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { app.repository.getActiveOverrides(nowInstant).isEmpty() }
        }
        runBlocking {
            val activeOverrides = app.repository.getActiveOverrides(nowInstant)
            assert(activeOverrides.isEmpty())
        }
    }

    // 9. Empty state inline Create Schedule button
    @Test
    fun emptyState_createScheduleButton_opensForm() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Create Schedule").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Create Schedule"))
        composeTestRule.onNodeWithText("Create Schedule").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Schedule Name Input").assertIsDisplayed()
    }

    // 10. Long schedule name handling
    @Test
    fun longScheduleName_rendersSafelyWithoutCrashing() {
        val longName = "Very Long Schedule Name For Intensive Study And Research Session That Extends Beyond Normal Display Bounds"
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val nowZdt = nowInstant.atZone(zone)

        runBlocking {
            app.repository.insertSchedule(
                Schedule(
                    name = longName,
                    startTime = LocalTime.of(14, 0),
                    endTime = LocalTime.of(16, 0),
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = nowZdt.toLocalDate()
                )
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(longName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(longName))
        composeTestRule.onNodeWithText(longName).assertIsDisplayed()

        // Click to open details
        composeTestRule.onNodeWithText(longName).performClick()
        composeTestRule.waitForIdle()

        // Details renders successfully without crashing
        composeTestRule.onNodeWithText("Schedule Details").assertIsDisplayed()
        composeTestRule.onNodeWithText(longName).assertIsDisplayed()
    }
}
