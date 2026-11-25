package org.imdtv.iptvtv.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    // Absolute path, not a bare name: Room 2.8's SupportSQLiteDriver compares the requested path to the configured
    // name by splitting on '/', which never matches a Windows '\' path. An absolute name equals the requested path
    // verbatim on every OS (Context.getDatabasePath returns absolute names unchanged).
    private val DB = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test").absolutePath

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v11ToV14AddsContentOffWithoutTheV13Index() {
        helper.createDatabase(DB, 11).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'','')")
        }
        helper.runMigrationsAndValidate(DB, 14, true, AppDatabase.MIGRATION_11_14).use { db ->
            db.query("SELECT name, contentOff FROM provider").use { c -> c.moveToFirst(); assertEquals("p", c.getString(0)); assertEquals("", c.getString(1)) }
        }
    }

    @Test fun v20ToV21AddsManagedServerIdWithoutChangingCredentials() {
        helper.createDatabase(DB, 20).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip,maxConnections,contentOff,enabled,mergeOn) VALUES (1,'managed','XTREAM','https://old.example','saved-user','saved-password',NULL,'TS','',NULL,'','',NULL,'',1,1)")
        }
        helper.runMigrationsAndValidate(DB, 21, true, AppDatabase.MIGRATION_20_21).use { db ->
            db.query("SELECT managedServerId, username, password FROM provider WHERE id = 1").use { c ->
                c.moveToFirst(); assertTrue(c.isNull(0)); assertEquals("saved-user", c.getString(1)); assertEquals("saved-password", c.getString(2))
            }
        }
    }

    @Test fun v1ToV2KeepsRowsAndAddsColumns() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL)")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned) VALUES (1,1,'LIVE','10','UK| News',3,1,0,0)")
            db.execSQL("INSERT INTO epg_source (id,providerId,url,priority,offsetMinutes,lastFetched) VALUES (1,1,'http://e',0,60,NULL)")
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT hidden, sortOrder, country FROM category").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals(3, c.getInt(1)); assertTrue(c.isNull(2)) }
            db.query("SELECT nameStrip, username FROM provider").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)); assertEquals("u", c.getString(1)) }
            db.query("SELECT offsetMinutes, name, lastStatus FROM epg_source").use { c -> c.moveToFirst(); assertEquals(60, c.getInt(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        }
    }

    @Test fun v2ToV3KeepsRowsAndAddsColumns() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'LIVE','10','UK| News',3,1,0,0,'GB')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,customName,logo,customLogo,number,customNumber,hidden,tvgId,epgChannelId,epgOffsetMinutes,streamUrl) VALUES (1,1,1,'101','BBC',NULL,'l.png','mine.png',1,NULL,0,NULL,NULL,NULL,'http://s')")
            db.execSQL("INSERT INTO epg_source (id,providerId,url,priority,offsetMinutes,lastFetched,name,lastStatus) VALUES (1,1,'http://e',0,60,NULL,NULL,NULL)")
        }
        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT customLogo, packLogo, builtinLogo FROM channel").use { c -> c.moveToFirst(); assertEquals("mine.png", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
            db.query("SELECT origin, offsetMinutes FROM epg_source").use { c -> c.moveToFirst(); assertEquals("PLAYLIST", c.getString(0)); assertEquals(60, c.getInt(1)) }
        }
    }

    @Test fun v4ToV5AddsColumns() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'UK|')")
            db.execSQL("INSERT INTO custom_group (id,providerId,name,sortOrder) VALUES (1,1,'Mine',0)")
        }
        helper.runMigrationsAndValidate(DB, 5, true).use { db ->
            db.query("SELECT name, placement FROM custom_group").use { c -> c.moveToFirst(); assertEquals("Mine", c.getString(0)); assertEquals("NORMAL", c.getString(1)) }
            db.query("SELECT nameStrip, categoryStrip FROM provider").use { c -> c.moveToFirst(); assertEquals("UK|", c.getString(0)); assertEquals("", c.getString(1)) }
        }
    }

    @Test fun v3ToV4AddsCountIndexesAndKeepsRows() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'MOVIE','10','Films',0,0,0,0,NULL)")
            db.execSQL("INSERT INTO movie (id,providerId,categoryId,remoteId,name,streamUrl,addedAt) VALUES (1,1,1,'5','Film','http://m',0)")
        }
        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT count(*) FROM movie").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('index_movie_providerId_categoryId','index_series_providerId_categoryId')")
                .use { c -> assertEquals(2, c.count) }
        }
    }

    @Test fun v6ToV7AddsRecordingTableAndMaxConnections() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'','')")
        }
        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            db.query("SELECT name, maxConnections FROM provider").use { c -> c.moveToFirst(); assertEquals("p", c.getString(0)); assertTrue(c.isNull(1)) }
            db.query("SELECT count(*) FROM recording").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun v8ToV9AddsFavouriteSortOrder() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL("INSERT INTO favourite (providerId,kind,itemId,addedAt) VALUES (1,'LIVE',5,100)")
        }
        helper.runMigrationsAndValidate(DB, 9, true).use { db ->
            db.query("SELECT itemId, addedAt, sortOrder FROM favourite").use { c -> c.moveToFirst(); assertEquals(5, c.getInt(0)); assertEquals(100, c.getInt(1)); assertEquals(0, c.getInt(2)) }
        }
    }

    @Test fun v9ToV10AddsProgressEpisode() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL("INSERT INTO progress (providerId,kind,itemId,positionMs,durationMs,updatedAt,completed) VALUES (1,'MOVIE',5,60000,7200000,100,0)")
        }
        helper.runMigrationsAndValidate(DB, 10, true).use { db ->
            db.query("SELECT itemId, positionMs, durationMs, updatedAt, completed, episode FROM progress").use { c ->
                c.moveToFirst(); assertEquals(5, c.getInt(0)); assertEquals(60000, c.getLong(1)); assertEquals(7200000, c.getLong(2))
                assertEquals(100, c.getLong(3)); assertEquals(0, c.getInt(4)); assertEquals("", c.getString(5))
            }
        }
    }

    @Test fun v7ToV8AddsChannelDrm() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip) VALUES (1,'p','M3U','http://x',NULL,NULL,NULL,'TS','',NULL,'','')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,logo,number,hidden,tvgId,streamUrl) VALUES (1,1,NULL,'r','BBC',NULL,NULL,0,NULL,'http://s')")
        }
        helper.runMigrationsAndValidate(DB, 8, true).use { db ->
            db.query("SELECT name, drmType, drmKey FROM channel").use { c -> c.moveToFirst(); assertEquals("BBC", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        }
    }

    @Test fun v10ToV11AddsChannelSortOrder() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip) VALUES (1,'p','M3U','http://x',NULL,NULL,NULL,'TS','',NULL,'','')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,logo,number,hidden,tvgId,streamUrl) VALUES (1,1,NULL,'r','BBC',NULL,7,1,NULL,'http://s')")
        }
        helper.runMigrationsAndValidate(DB, 11, true).use { db ->
            db.query("SELECT name, number, hidden, sortOrder FROM channel").use { c ->
                c.moveToFirst(); assertEquals("BBC", c.getString(0)); assertEquals(7, c.getInt(1)); assertEquals(1, c.getInt(2)); assertTrue(c.isNull(3))
            }
        }
    }
/** The steps added since the managed releases: renames, offsets, merges and the global merge settings. */
    @Test fun v14ToV19KeepsChannelEditsAndAddsMergeSettings() {
        helper.createDatabase(DB, 14).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip,maxConnections,contentOff) VALUES (1,'p','M3U','http://x',NULL,NULL,NULL,'TS','',NULL,'','',NULL,'')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'LIVE','10','News',0,1,0,0,'GB')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,customName,logo,customLogo,number,customNumber,hidden,tvgId,epgChannelId,epgOffsetMinutes,streamUrl,sortOrder) VALUES (1,1,1,'r','BBC One',NULL,NULL,NULL,7,NULL,1,NULL,NULL,NULL,'http://s',NULL)")
        }
        helper.runMigrationsAndValidate(DB, 19, true).use { db ->
            db.query("SELECT name, customNumber, hidden, epgOffsetMinutes FROM channel WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("BBC One", c.getString(0)); assertEquals(1, c.getInt(2))
            }
            db.query("SELECT name, mergeDuplicates FROM provider WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("p", c.getString(0))
            }
        }
    }

    /** 19 -> 20 moves the merge settings out of the playlist row: the playlist and its channels must survive it. */
    @Test fun v19ToV20DropsPerPlaylistMergeSettingsAndKeepsRows() {
        helper.createDatabase(DB, 19).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip,maxConnections,contentOff,mergeDuplicates,mergeBy,mergeAcross) VALUES (1,'p','M3U','http://x','u','pw',NULL,'TS','',NULL,'UK|','',NULL,'',1,'NAME',0)")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'LIVE','10','News',0,0,0,0,'GB')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,customName,logo,customLogo,number,customNumber,hidden,tvgId,epgChannelId,epgOffsetMinutes,streamUrl,sortOrder) VALUES (1,1,1,'r','BBC One','My BBC',NULL,NULL,7,7,1,NULL,NULL,30,'http://s',2)")
        }
        helper.runMigrationsAndValidate(DB, 20, true).use { db ->
            db.query("SELECT username, password, nameStrip FROM provider WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("u", c.getString(0)); assertEquals("pw", c.getString(1)); assertEquals("UK|", c.getString(2))
            }
            db.query("SELECT customName, customNumber, epgOffsetMinutes, hidden, sortOrder FROM channel WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("My BBC", c.getString(0)); assertEquals(7, c.getInt(1)); assertEquals(30, c.getInt(2)); assertEquals(1, c.getInt(3)); assertEquals(2, c.getInt(4))
            }
            db.query("PRAGMA table_info(provider)").use { c ->
                val names = ArrayList<String>(); while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
                assertTrue("merge columns must be gone", names.none { it in setOf("mergeDuplicates", "mergeBy", "mergeAcross") })
            }
        }
    }

    @Test fun v21ToV22KeepsPlaylistsAndChannels() {
        helper.createDatabase(DB, 21).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip,maxConnections,contentOff,enabled,mergeOn,managedServerId) VALUES (1,'p','XTREAM','https://x','u','pw',NULL,'TS','',NULL,'','',2,'',1,1,'srv')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'LIVE','10','News',0,0,0,0,'GB')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,customName,logo,customLogo,number,customNumber,hidden,tvgId,epgChannelId,epgOffsetMinutes,streamUrl,sortOrder) VALUES (1,1,1,'r','BBC One',NULL,NULL,NULL,7,NULL,0,NULL,NULL,NULL,'http://s',NULL)")
        }
        helper.runMigrationsAndValidate(DB, 22, true).use { db ->
            db.query("SELECT managedServerId, username, maxConnections FROM provider WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("srv", c.getString(0)); assertEquals("u", c.getString(1)); assertEquals(2, c.getInt(2))
            }
            db.query("SELECT count(*) FROM channel").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    /**
     * A customer on the oldest released database updating to this build: every step in one go, with a playlist,
     * category and channel present throughout. Room picks the longest path (11 -> 14), so this also proves the
     * shortcut does not lose the columns the later steps expect.
     */
    @Test fun v1ToCurrentKeepsAPlaylistThroughEveryStep() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync) VALUES (1,'My list','XTREAM','http://x','user','secret',NULL,'TS','',NULL)")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned) VALUES (1,1,'LIVE','10','UK| News',3,1,0,0)")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,logo,number,hidden,tvgId,streamUrl) VALUES (1,1,1,'101','BBC One','l.png',7,1,'bbc.one','http://s')")
        }
        helper.runMigrationsAndValidate(DB, 22, true, AppDatabase.MIGRATION_11_14, AppDatabase.MIGRATION_20_21).use { db ->
            db.query("SELECT name, username, password FROM provider WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("My list", c.getString(0)); assertEquals("user", c.getString(1)); assertEquals("secret", c.getString(2))
            }
            db.query("SELECT name, hidden, sortOrder FROM category WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("UK| News", c.getString(0)); assertEquals(1, c.getInt(1)); assertEquals(3, c.getInt(2))
            }
            db.query("SELECT name, number, hidden, streamUrl FROM channel WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals("BBC One", c.getString(0)); assertEquals(7, c.getInt(1)); assertEquals(1, c.getInt(2)); assertEquals("http://s", c.getString(3))
            }
        }
    }
}
