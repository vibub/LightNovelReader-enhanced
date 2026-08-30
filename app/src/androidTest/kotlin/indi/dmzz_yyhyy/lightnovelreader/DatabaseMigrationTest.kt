package indi.dmzz_yyhyy.lightnovelreader

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class DatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var helper: SupportSQLiteOpenHelper? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "migration-test-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        createVersion22Database()
    }

    @After
    fun tearDown() {
        helper?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration22To23AddsSourceScopedKeysAndPreservesRows() {
        helper = openVersion23Database()
        val database = helper!!.writableDatabase

        assertEquals(
            listOf("source_id", "id"),
            database.primaryKeyColumns("book_information")
        )
        assertEquals(
            listOf("source_id", "book_id", "volume_id"),
            database.primaryKeyColumns("volume")
        )
        assertEquals(
            listOf("source_id", "book_id", "id"),
            database.primaryKeyColumns("chapter_information")
        )

        database.query(
            "select source_id, id, title from book_information where id = ?",
            arrayOf("legacy-book")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(-1, cursor.getInt(0))
            assertEquals("legacy-book", cursor.getString(1))
            assertEquals("Legacy book", cursor.getString(2))
        }
        database.query(
            "select source_id, book_id, volume_id from volume where volume_id = ?",
            arrayOf("legacy-volume")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(-1, cursor.getInt(0))
            assertEquals("legacy-book", cursor.getString(1))
            assertEquals("legacy-volume", cursor.getString(2))
        }
        database.query(
            "select source_id, book_id, id, title from chapter_information where id = ?",
            arrayOf("legacy-chapter")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(-1, cursor.getInt(0))
            assertEquals("", cursor.getString(1))
            assertEquals("legacy-chapter", cursor.getString(2))
            assertEquals("Legacy chapter", cursor.getString(3))
        }
    }

    private fun createVersion22Database() {
        val helper = createHelper(
            callback = object : SupportSQLiteOpenHelper.Callback(22) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE book_information (
                            id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            subtitle TEXT NOT NULL,
                            cover_uri TEXT NOT NULL,
                            author TEXT NOT NULL,
                            description TEXT NOT NULL,
                            tags TEXT NOT NULL,
                            publishing_house TEXT NOT NULL,
                            word_count TEXT NOT NULL,
                            last_update TEXT NOT NULL,
                            is_complete INTEGER NOT NULL,
                            PRIMARY KEY(id)
                        )
                        """
                    )
                    database.execSQL(
                        """
                        CREATE TABLE volume (
                            book_id TEXT NOT NULL,
                            volume_id TEXT NOT NULL,
                            volume_title TEXT NOT NULL,
                            chapter_id_list TEXT NOT NULL,
                            volume_index INTEGER NOT NULL,
                            PRIMARY KEY(volume_id)
                        )
                        """
                    )
                    database.execSQL(
                        """
                        CREATE TABLE chapter_information (
                            id TEXT NOT NULL,
                            title TEXT NOT NULL,
                            PRIMARY KEY(id)
                        )
                        """
                    )
                    database.execSQL(
                        """
                        CREATE TABLE download_task (
                            source_id INTEGER NOT NULL,
                            book_id TEXT NOT NULL,
                            state TEXT NOT NULL,
                            progress REAL NOT NULL,
                            total INTEGER NOT NULL,
                            processed INTEGER NOT NULL,
                            source_key TEXT NOT NULL DEFAULT '',
                            current_chapter_id TEXT,
                            current_chapter_title TEXT,
                            estimated_bytes INTEGER NOT NULL DEFAULT 0,
                            written_bytes INTEGER NOT NULL DEFAULT 0,
                            waiting_reason TEXT,
                            error_message TEXT,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(source_id, book_id)
                        )
                        """
                    )
                    database.execSQL(
                        """
                        INSERT INTO download_task(
                            source_id, book_id, state, progress, total, processed,
                            updated_at
                        ) VALUES (7, 'legacy-download', 'RUNNING', 0, 0, 0, 0)
                        """
                    )
                    database.execSQL(
                        """
                        INSERT INTO book_information VALUES (
                            'legacy-book', 'Legacy book', '', '', '', '', '', '',
                            '1', '2026-01-01T00:00:00', 0
                        )
                        """
                    )
                    database.execSQL(
                        """
                        INSERT INTO volume VALUES (
                            'legacy-book', 'legacy-volume', 'Legacy volume', 'legacy-chapter', 0
                        )
                        """
                    )
                    database.execSQL(
                        """
                        INSERT INTO chapter_information VALUES (
                            'legacy-chapter', 'Legacy chapter'
                        )
                        """
                    )
                }

                override fun onUpgrade(
                    database: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            }
        )
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun migration23To24AddsQueueAllWithSafeDefault() {
        helper = openVersion24Database()
        val database = helper!!.writableDatabase

        database.query(
            "select queue_all from download_task where source_id = ? and book_id = ?",
            arrayOf("7", "legacy-download")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration24To25AddsConstraintsKeyWithSafeDefault() {
        helper = openVersion25Database()
        val database = helper!!.writableDatabase

        database.query(
            "select constraints_key from download_task where source_id = ? and book_id = ?",
            arrayOf("7", "legacy-download")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
    }

    @Test
    fun migration25To26RemovesEstimatedBytesAndKeepsWrittenBytes() {
        helper = openVersion26Database()
        val database = helper!!.writableDatabase

        val columns = database.query("pragma table_info(download_task)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue("estimated_bytes" !in columns)
        assertTrue("written_bytes" in columns)
        database.query(
            "select state from download_task where source_id = ? and book_id = ?",
            arrayOf("7", "legacy-download")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("RUNNING", cursor.getString(0))
        }
    }

    private fun openVersion23Database(): SupportSQLiteOpenHelper = createHelper(
        callback = object : SupportSQLiteOpenHelper.Callback(23) {
            override fun onCreate(database: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
                database: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                if (oldVersion < 23) {
                    LightNovelReaderDatabase.MIGRATION_22_23.migrate(database)
                }
            }
        }
    )

    private fun openVersion24Database(): SupportSQLiteOpenHelper = createHelper(
        callback = object : SupportSQLiteOpenHelper.Callback(24) {
            override fun onCreate(database: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
                database: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                if (oldVersion < 23) {
                    LightNovelReaderDatabase.MIGRATION_22_23.migrate(database)
                }
                if (oldVersion < 24) {
                    LightNovelReaderDatabase.MIGRATION_23_24.migrate(database)
                }
            }
        }
    )

    private fun openVersion25Database(): SupportSQLiteOpenHelper = createHelper(
        callback = object : SupportSQLiteOpenHelper.Callback(25) {
            override fun onCreate(database: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
                database: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                if (oldVersion < 23) {
                    LightNovelReaderDatabase.MIGRATION_22_23.migrate(database)
                }
                if (oldVersion < 24) {
                    LightNovelReaderDatabase.MIGRATION_23_24.migrate(database)
                }
                if (oldVersion < 25) {
                    LightNovelReaderDatabase.MIGRATION_24_25.migrate(database)
                }
            }
        }
    )

    private fun openVersion26Database(): SupportSQLiteOpenHelper = createHelper(
        callback = object : SupportSQLiteOpenHelper.Callback(26) {
            override fun onCreate(database: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
                database: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                if (oldVersion < 23) {
                    LightNovelReaderDatabase.MIGRATION_22_23.migrate(database)
                }
                if (oldVersion < 24) {
                    LightNovelReaderDatabase.MIGRATION_23_24.migrate(database)
                }
                if (oldVersion < 25) {
                    LightNovelReaderDatabase.MIGRATION_24_25.migrate(database)
                }
                if (oldVersion < 26) {
                    LightNovelReaderDatabase.MIGRATION_25_26.migrate(database)
                }
            }
        }
    )

    private fun createHelper(
        callback: SupportSQLiteOpenHelper.Callback
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
    )

    private fun SupportSQLiteDatabase.primaryKeyColumns(tableName: String): List<String> =
        query("pragma table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            buildList {
                while (cursor.moveToNext()) {
                    val primaryKeyPosition = cursor.getInt(primaryKeyIndex)
                    if (primaryKeyPosition > 0) {
                        add(primaryKeyPosition to cursor.getString(nameIndex))
                    }
                }
            }.sortedBy { it.first }.map { it.second }
        }
}
