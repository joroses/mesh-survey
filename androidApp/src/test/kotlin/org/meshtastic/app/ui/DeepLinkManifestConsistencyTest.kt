/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.app.ui

import org.junit.runner.RunWith
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.navigation.DEEP_LINK_BASE_URI
import org.meshtastic.core.navigation.DeepLinkRouter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards [DeepLinkRouter] and the deep-link intent-filters in `androidApp/src/main/AndroidManifest.xml`.
 *
 * Mesh Survey uses its own `meshsurvey://meshsurvey` scheme and does not claim `meshtastic.org` or `meshtastic://`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkManifestConsistencyTest {

    @Test
    fun `every canonical segment is actually routed by DeepLinkRouter`() {
        DeepLinkRouter.topLevelPathSegments.forEach { segment ->
            assertNotNull(
                DeepLinkRouter.route(CommonUri.parse("$DEEP_LINK_BASE_URI/$segment")),
                "DeepLinkRouter.topLevelPathSegments lists /$segment but route() has no branch for it — " +
                    "add the branch or remove the segment from the set",
            )
        }
    }

    @Test
    fun `manifest declares meshsurvey scheme and does not claim meshtastic domain or scheme`() {
        val dataElements = manifestDataElements()
        assertTrue(
            dataElements.any {
                it.getAttribute("android:scheme") == "meshsurvey" && it.getAttribute("android:host") == "meshsurvey"
            },
            "AndroidManifest.xml must declare <data android:scheme=\"meshsurvey\" android:host=\"meshsurvey\" />",
        )
        assertFalse(
            dataElements.any { it.getAttribute("android:host").equals("meshtastic.org", ignoreCase = true) },
            "AndroidManifest.xml must not claim meshtastic.org",
        )
        assertFalse(
            dataElements.any { it.getAttribute("android:scheme").equals("meshtastic", ignoreCase = true) },
            "AndroidManifest.xml must not claim the meshtastic:// scheme",
        )
    }

    private fun manifestDataElements(): List<Element> {
        val manifest = manifestFile()
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                // Harden against XXE even though we only parse our own manifest.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
        val document = factory.newDocumentBuilder().parse(manifest)
        val elements = document.getElementsByTagName("data")
        return (0 until elements.length).map { elements.item(it) as Element }
    }

    private fun manifestFile(): File = listOf("src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
        .map(::File)
        .firstOrNull(File::exists)
        ?: fail("Could not locate AndroidManifest.xml from working directory ${File(".").absolutePath}")
}
