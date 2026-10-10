package jp.yuki.a6000transfer.sony

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DlnaDateTest {
    @Test fun `DIDL項目のdc dateを取り出す`() {
        val xml = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="1"><dc:title>LRG_001.JPG</dc:title><dc:date>2020-01-02T03:04:05+09:00</dc:date>
            <upnp:class>object.item.imageItem.photo</upnp:class><res protocolInfo="http-get:*:image/jpeg:*">http://camera/1.jpg</res></item>
            <item id="2"><dc:title>LRG_002.JPG</dc:title><res>http://camera/2.jpg</res></item>
        </DIDL-Lite>""".trimIndent()
        val items = DlnaClient.parseDidl(xml)
        assertEquals("2020-01-02T03:04:05+09:00", items[0].date)
        assertEquals("", items[1].date)
        assertEquals("http://camera/1.jpg", items[0].url)
    }
}
