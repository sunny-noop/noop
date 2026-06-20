package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the additive v11 -> v12 Room migration (the `ppgWaveformSample` raw-PPG-grid table). This
 * environment has no Robolectric / Room-testing, so the migration's SQL is exposed as an internal
 * constant ([WhoopDatabase.PPG_WAVEFORM_CREATE_SQL]) and pinned here to Room's generated-schema shape:
 *
 *  - composite PRIMARY KEY (deviceId, ts, sampleIdx) in declaration order.
 *  - every column NOT NULL with NO SQL DEFAULT (a Kotlin construction default never reaches the schema).
 *  - ADDITIVE: a single CREATE statement; no ALTER/DROP/DELETE/UPDATE/INSERT.
 *
 * Mirrors [LabMarkerMigrationTest] and the MIGRATION_5_6 (`ppgHrSample`) precedent.
 */
class PpgWaveformMigrationTest {

    @Test
    fun migration_isAdditive_singleCreateStatement() {
        val sql = WhoopDatabase.PPG_WAVEFORM_CREATE_SQL.trimStart().uppercase()
        assertTrue("must be a CREATE statement", sql.startsWith("CREATE"))
        for (banned in listOf("DROP ", "ALTER ", "DELETE ", "UPDATE ", "INSERT ")) {
            assertTrue("additive migration must not contain '$banned'", !sql.contains(banned))
        }
    }

    @Test
    fun migration_createsTable_withRoomSchemaShape() {
        val create = WhoopDatabase.PPG_WAVEFORM_CREATE_SQL

        assertEquals(
            "CREATE TABLE IF NOT EXISTS `ppgWaveformSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `sampleIdx` INTEGER NOT NULL, `value` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`, `sampleIdx`))",
            create,
        )

        // Composite primary key in declaration order.
        assertTrue(create.contains("PRIMARY KEY(`deviceId`, `ts`, `sampleIdx`)"))

        // Every column NOT NULL, with the right Room types.
        assertTrue(create.contains("`deviceId` TEXT NOT NULL"))
        assertTrue(create.contains("`ts` INTEGER NOT NULL"))
        assertTrue(create.contains("`sampleIdx` INTEGER NOT NULL"))
        assertTrue(create.contains("`value` INTEGER NOT NULL"))

        // No SQL DEFAULT anywhere (a Kotlin construction default never reaches the schema).
        assertTrue("no SQL DEFAULT in the CREATE TABLE", !create.uppercase().contains("DEFAULT"))
    }
}
