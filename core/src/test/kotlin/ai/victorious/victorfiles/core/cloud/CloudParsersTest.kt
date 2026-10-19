package ai.victorious.victorfiles.core.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudParsersTest {
    @Test fun s3ListXml() {
        val xml = """
            <?xml version="1.0"?>
            <ListBucketResult>
              <IsTruncated>false</IsTruncated>
              <Contents><Key>dir/file.txt</Key><Size>123</Size><LastModified>2026-01-02T03:04:05.000Z</LastModified></Contents>
              <CommonPrefixes><Prefix>dir/sub/</Prefix></CommonPrefixes>
            </ListBucketResult>
        """.trimIndent()
        val l = S3Xml.parseList(xml)
        assertEquals(1, l.objects.size)
        assertEquals("dir/file.txt", l.objects[0].key)
        assertEquals(123L, l.objects[0].size)
        assertTrue(l.objects[0].lastModifiedMs > 0)
        assertEquals(listOf("dir/sub/"), l.prefixes)
        assertFalse(l.truncated)
    }

    @Test fun dropboxList() {
        val json = """
            {"entries":[
              {".tag":"folder","name":"docs","path_display":"/docs"},
              {".tag":"file","name":"a.txt","path_display":"/a.txt","size":10,"server_modified":"2026-01-02T03:04:05Z"}
            ],"cursor":"c","has_more":false}
        """.trimIndent()
        val l = DropboxParser.parseList(json)
        assertEquals(2, l.entries.size)
        assertEquals("folder", l.entries[0].tag)
        assertEquals(10L, l.entries[1].size)
        assertTrue(DropboxParser.isoToMs(l.entries[1].serverModified) > 0)
    }

    @Test fun graphChildren() {
        val json = """
            {"value":[
              {"name":"Photos","size":0,"lastModifiedDateTime":"2026-01-02T03:04:05Z","folder":{"childCount":3}},
              {"name":"note.txt","size":42,"lastModifiedDateTime":"2026-01-02T03:04:05Z"}
            ]}
        """.trimIndent()
        val c = GraphParser.parseChildren(json)
        assertEquals(2, c.value.size)
        assertNotNull(c.value[0].folder)
        assertNull(c.value[1].folder)
        assertEquals(42L, c.value[1].size)
    }

    @Test fun driveList() {
        val json = """
            {"files":[
              {"id":"1","name":"Folder","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-01-02T03:04:05Z"},
              {"id":"2","name":"f.bin","mimeType":"application/octet-stream","size":"99","modifiedTime":"2026-01-02T03:04:05Z"}
            ]}
        """.trimIndent()
        val l = GDriveParser.parseList(json)
        assertEquals(2, l.files.size)
        assertTrue(GDriveParser.isFolder(l.files[0]))
        assertEquals("99", l.files[1].size)
    }
}
