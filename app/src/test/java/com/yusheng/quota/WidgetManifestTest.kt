package com.yusheng.quota

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = QuotaApp::class)
class WidgetManifestTest {
    @Test fun launcherOffersTwoFixedWidgetSizesWithConfiguration() {
        val app = RuntimeEnvironment.getApplication() as QuotaApp
        val androidNs = "http://schemas.android.com/apk/res/android"
        for ((name, width) in listOf("SmallQuotaWidget" to 2, "WideQuotaWidget" to 4)) {
            val component = ComponentName(app.packageName, "${app.packageName}.widget.$name")
            val receiver = try {
                app.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
            } catch (_: PackageManager.NameNotFoundException) {
                fail("Missing $name in launcher widget manifest")
                return
            }
            val xmlId = receiver.metaData.getInt("android.appwidget.provider")
            app.resources.getXml(xmlId).use { xml ->
                while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
                assertEquals(width, xml.getAttributeIntValue(androidNs, "targetCellWidth", 0))
                assertEquals(2, xml.getAttributeIntValue(androidNs, "targetCellHeight", 0))
                assertEquals(0, xml.getAttributeIntValue(androidNs, "resizeMode", -1))
                assertTrue(xml.getAttributeValue(androidNs, "configure").endsWith("WidgetConfigActivity"))
            }
        }
    }
}
