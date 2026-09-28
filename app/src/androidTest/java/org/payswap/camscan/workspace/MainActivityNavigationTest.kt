package org.payswap.camscan.workspace

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.MainActivity
import org.payswap.camscan.R

/**

Shell navigation smoke test (CAMSCAN-PROD-005).

NOTE: runs at the Tech Lead's integration station (lab AVD) — the worker

sandbox has no Android SDK or emulator.
*/
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {

@Test
fun homeRendersThenLibraryRoundTrip() {
ActivityScenario.launch(MainActivity::class.java)

// Home renders: new-scan button + honest empty state (repository starts empty).
onView(withId(R.id.home_new_scan_button)).check(matches(isDisplayed()))
onView(withId(R.id.home_empty_state)).check(matches(isDisplayed()))

// Open Library; assert the list screen (empty state shown, list view present).
onView(withId(R.id.home_open_library)).perform(click())
onView(withId(R.id.library_empty_state)).check(matches(isDisplayed()))
onView(withId(R.id.library_documents_list)).check(ViewAssertions.matches(isDisplayed()))

// Back returns to Home.
pressBack()
onView(withId(R.id.home_new_scan_button)).check(matches(isDisplayed()))
}

@Test
fun newScanOpensPlaceholderScanSurfaceThenReturnsHome() {
ActivityScenario.launch(MainActivity::class.java)

// ScanLauncher seam: placeholder scan surface attaches into the shell container.
onView(withId(R.id.home_new_scan_button)).perform(click())
onView(withId(R.id.placeholder_scan_notice)).check(matches(isDisplayed()))

pressBack()
onView(withId(R.id.home_new_scan_button)).check(matches(isDisplayed()))
}

}
