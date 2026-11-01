package de.wanderlogplus.app.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage

/** Screenshot folder (relative to the app module); never committed. */
const val SCREENSHOT_DIR = "build/outputs/roborazzi"

/** Always records [name].png (no comparison), independent of the roborazzi.test.* Gradle properties. */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.recordScreenshot(name: String) {
    captureRoboImage("$SCREENSHOT_DIR/$name.png", roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
}
