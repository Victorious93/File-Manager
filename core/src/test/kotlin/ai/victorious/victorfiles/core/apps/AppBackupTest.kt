package ai.victorious.victorfiles.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupTest {
    @Test fun folderAndFileNames() {
        val name = AppBackup.folderName("com.example.app", 42, atMs = 0L)
        assertTrue(name.startsWith("com.example.app_42_"))
        assertEquals("com.example.app.apk", AppBackup.singleApkName("com.example.app"))
        assertEquals("com.example.app.apks", AppBackup.apksBundleName("com.example.app"))
        assertEquals("com.example.app_data.tar", AppBackup.dataArchiveName("com.example.app"))
    }

    @Test fun manifestRoundTrip() {
        val m = AppBackupManifest(
            packageName = "com.a",
            label = "A",
            versionName = "1.0",
            versionCode = 3,
            backedUpAtEpochMs = 99,
            includeData = true,
            apks = listOf(BackupApkEntry("base.apk", "base"), BackupApkEntry("config.apk", "split")),
            dataArchive = "com.a_data.tar",
        )
        val again = AppBackup.readManifest(AppBackup.writeManifest(m))
        assertEquals(m, again)
    }

    @Test fun filterSort() {
        val apps = listOf(
            AppBackup.AppRow("b", "Beta", false, 200, 2),
            AppBackup.AppRow("a", "Alpha", true, 50, 9),
            AppBackup.AppRow("c", "Gamma", false, 100, 5),
        )
        val user = AppBackup.filterAndSort(apps, "", userOnly = true, systemOnly = false, sort = AppBackup.Sort.NAME)
        assertEquals(listOf("Beta", "Gamma"), user.map { it.label })
        val bySize = AppBackup.filterAndSort(apps, "", false, false, AppBackup.Sort.SIZE, ascending = false)
        assertEquals("Beta", bySize.first().label)
        val q = AppBackup.filterAndSort(apps, "amm", false, false, AppBackup.Sort.NAME)
        assertEquals(listOf("Gamma"), q.map { it.label })
        assertEquals("base", AppBackup.roleFor("base.apk", 0))
        assertEquals("split", AppBackup.roleFor("split_config.arm64.apk", 1))
    }
}
