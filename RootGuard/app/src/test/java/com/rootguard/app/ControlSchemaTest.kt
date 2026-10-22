package com.rootguard.app

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.rootguard.app.data.db.ControlDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Proves the Room mapping is equivalent to handoff/database/control_v1.sql: same tables, columns, types,
 * nullability, primary keys, foreign keys and unique indexes as the v1 fixture, and the same CHECK /
 * partial-unique behaviour (via triggers), mirroring handoff tests/test_handoff.py.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ControlSchemaTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val room = ControlDatabase.build(ctx, name = null)
    private val db: SupportSQLiteDatabase = room.openHelper.writableDatabase

    @After fun close() = room.close()

    private fun fixture(): SQLiteDatabase {
        val f = File(ctx.cacheDir, "control_v1.sqlite")
        javaClass.classLoader!!.getResourceAsStream("control_v1.sqlite").use { input -> f.outputStream().use { input.copyTo(it) } }
        return SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun rows(q: String, query: (String) -> android.database.Cursor): List<List<String?>> = query(q).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    @Test
    fun `room schema matches the v1 fixture`() {
        val fx = fixture()
        val fq: (String) -> android.database.Cursor = { fx.rawQuery(it, null) }
        val rq: (String) -> android.database.Cursor = { db.query(it) }
        val tablesSql = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name"
        val tables = rows(tablesSql, fq).map { it[0]!! }
        assertEquals(15, tables.size)
        assertEquals(tables, rows(tablesSql, rq).map { it[0]!! })
        for (t in tables) {
            // PRAGMA table_info: cid, name, type, notnull, dflt_value, pk
            val a = rows("PRAGMA table_info($t)", fq).associateBy { it[1] }
            val b = rows("PRAGMA table_info($t)", rq).associateBy { it[1] }
            assertEquals("$t columns", a.keys, b.keys)
            for ((col, x) in a) {
                val y = b.getValue(col)
                assertEquals("$t.$col type", x[2]!!.uppercase(), y[2]!!.uppercase())
                assertEquals("$t.$col pk", x[5], y[5])
                // SQLite lets non-INTEGER primary keys be NULL; Room adds NOT NULL there, which is stricter.
                if (x[5] == "0") assertEquals("$t.$col notnull", x[3], y[3])
            }
            fun fks(q: (String) -> android.database.Cursor) = rows("PRAGMA foreign_key_list($t)", q).map { Triple(it[2], it[3], it[4]) }.toSet()
            assertEquals("$t foreign keys", fks(fq), fks(rq))
            fun uniques(q: (String) -> android.database.Cursor) = rows("PRAGMA index_list($t)", q)
                .filter { it[2] == "1" && it[3] != "pk" && it[4] == "0" }
                .map { idx -> rows("PRAGMA index_info(${idx[1]})", q).map { it[2] } }.toSet()
            assertEquals("$t unique indexes", uniques(fq), uniques(rq))
        }
        fx.close()
    }

    private fun expectConstraint(sql: String) {
        try {
            db.execSQL(sql)
            fail("expected constraint failure: $sql")
        } catch (_: SQLiteConstraintException) {
        } catch (e: android.database.SQLException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("constraint", ignoreCase = true))
        }
    }

    @Test
    fun `active uid reuse requires closing the previous principal`() {
        db.execSQL("INSERT INTO principal VALUES('p1',0,10001,10,NULL,'EXACT')")
        expectConstraint("INSERT INTO principal VALUES('p2',0,10001,20,NULL,'EXACT')")
        db.execSQL("UPDATE principal SET valid_until_ms=20 WHERE principal_id='p1'")
        db.execSQL("INSERT INTO principal VALUES('p2',0,10001,21,NULL,'EXACT')")
        expectConstraint("UPDATE principal SET valid_until_ms=NULL WHERE principal_id='p1'")
    }

    @Test
    fun `rule foreign keys, ports and enums are enforced`() {
        db.execSQL("INSERT INTO profile VALUES('prof','Test','BALANCED','ALLOW','NOTIFY','PRESERVE_LAST_GOOD',1)")
        db.execSQL("INSERT INTO policy_revision VALUES('r','prof',NULL,1,'{}','hash','1')")
        db.execSQL("INSERT INTO policy_rule VALUES('rule','r',NULL,'DENY','USER','WIFI','ANY',NULL,'TCP',80,90,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO policy_rule VALUES('bad','r',NULL,'DENY','USER','WIFI','ANY',NULL,'TCP',100,90,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO policy_rule VALUES('bad2','r','nonexistent-principal','DENY','USER','WIFI','ANY',NULL,'TCP',80,90,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO policy_rule VALUES('bad3','r',NULL,'DENY','USER','WIFI','ANY',NULL,'ICMP',80,90,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO policy_rule VALUES('bad4','r',NULL,'MAYBE','USER','WIFI','ANY',NULL,'TCP',NULL,NULL,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO policy_rule VALUES('bad5','r',NULL,'DENY','USER','WIFI','IP',NULL,'ANY',NULL,NULL,0,1,NULL,NULL)")
        expectConstraint("INSERT INTO runtime_state VALUES(2,NULL,NULL,NULL,'OFF',1)")
        expectConstraint("INSERT INTO apply_transaction VALUES('t',NULL,'r','DONE',1,1,NULL,NULL)")
        expectConstraint("INSERT INTO profile VALUES('p2','x','YOLO','ALLOW','NOTIFY','PRESERVE_LAST_GOOD',1)")
    }

    @Test
    fun `one active installation per package`() {
        db.execSQL("INSERT INTO app_installation VALUES('a1',0,'com.x','g1','s',1,NULL)")
        expectConstraint("INSERT INTO app_installation VALUES('a2',0,'com.x','g2','s',2,NULL)")
        db.execSQL("UPDATE app_installation SET removed_ms=3 WHERE app_id='a1'")
        db.execSQL("INSERT INTO app_installation VALUES('a2',0,'com.x','g2','s',4,NULL)")
        expectConstraint("INSERT INTO app_installation VALUES('a3',0,'com.y','g','s',5,4)")
    }
}
