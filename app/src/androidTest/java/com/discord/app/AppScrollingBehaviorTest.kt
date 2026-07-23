package com.discord.app

import android.util.AttributeSet
import android.util.Xml
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.AppBarLayout
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AppScrollingBehaviorTest {
    @Test
    fun preservesScrollingBehaviorAndReferenceInsetsHandling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val parent = CoordinatorLayout(context)
            val child = View(context)
            val appBar = AppBarLayout(context)
            val attrs = emptyAttributeSet()
            val scrolling = AppScrollingViewBehavior(context, attrs)
            val nested = AppScrollingNestedViewBehavior(context, attrs)

            assertTrue(scrolling.layoutDependsOn(parent, child, appBar))
            assertTrue(nested.layoutDependsOn(parent, child, appBar))

            child.setPadding(10, 20, 30, 40)
            val firstInsets = insets(1, 2, 3, 4)
            assertSame(firstInsets, scrolling.onApplyWindowInsets(parent, child, firstInsets))
            assertEquals(listOf(11, 20, 33, 46), child.padding())

            scrolling.onApplyWindowInsets(parent, child, insets(5, 6, 7, 8))
            assertEquals(listOf(15, 20, 37, 54), child.padding())

            child.setPadding(10, 20, 30, 40)
            assertSame(firstInsets, nested.onApplyWindowInsets(parent, child, firstInsets))
            assertEquals(listOf(10, 20, 30, 40), child.padding())
        }
    }

    private fun emptyAttributeSet(): AttributeSet {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader("<view />"))
        while (parser.next() != XmlPullParser.START_TAG) Unit
        return Xml.asAttributeSet(parser)
    }

    private fun insets(left: Int, top: Int, right: Int, bottom: Int) =
        WindowInsetsCompat.Builder()
            .setSystemWindowInsets(Insets.of(left, top, right, bottom))
            .build()

    private fun View.padding() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
}
