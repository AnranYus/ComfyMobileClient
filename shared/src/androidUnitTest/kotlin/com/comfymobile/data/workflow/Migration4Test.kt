package com.comfymobile.data.workflow

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.data.db.newInMemoryComfyMobileDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per ADR-0006 §6b — the explicit proof that `4.sqm` adds
 * `workflow.imported_original_json`, backfills from `original_json`,
 * and round-trips fresh rows through the new column.
 *
 * Two of the three cases hand-drive a v3-shape database (the
 * pre-4.sqm head schema), then call `Schema.migrate(driver, 3, 4)`
 * to apply only `4.sqm`. The third case starts at head-schema v5
 * (post-4.sqm) and exercises the importer-shape `upsertWorkflow`
 * call to verify the new column round-trips.
 *
 * The test doubles as proof that the SQLDelight bundled SQLite
 * driver executes this project's first multi-statement `.sqm`
 * correctly. Failure of any case here is the signal to switch to
 * the `4.sqm` / `4b.sqm` split fallback documented in ADR-0006
 * §Consequences.
 */
class Migration4Test {

    // The v3-shape CREATE TABLE — what existed in production rows on
    // OPLUS / @nothing's device before 4.sqm. Kept verbatim here so
    // the test does not need to time-travel through git history.
    private val workflowV3CreateTable = """
        CREATE TABLE workflow (
            id TEXT NOT NULL PRIMARY KEY,
            friendly_name TEXT NOT NULL,
            format TEXT NOT NULL,
            original_json TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            imported_at INTEGER NOT NULL,
            last_opened_at INTEGER,
            last_run_prompt_id TEXT
        );
    """.trimIndent()

    private val workflowV3IndexCreate = """
        CREATE INDEX workflow_recents
            ON workflow(last_opened_at DESC, imported_at DESC);
    """.trimIndent()

    @Test
    fun m4_1_addsImportedOriginalJsonColumn_inExpectedShape() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Build the v3 baseline by hand so we can validate the
        // 4.sqm produces the head schema's expected layout.
        driver.execute(null, workflowV3CreateTable, 0)
        driver.execute(null, workflowV3IndexCreate, 0)

        ComfyMobileDb.Schema.migrate(driver, 4, 5)

        val columnInfo = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(workflow);",
            parameters = 0,
            mapper = { cursor ->
                val rows = mutableListOf<Triple<String, String, Long>>()
                while (cursor.next().value) {
                    val name = cursor.getString(1) ?: ""
                    val type = cursor.getString(2) ?: ""
                    val notNull = cursor.getLong(3) ?: 0L
                    rows += Triple(name, type, notNull)
                }
                app.cash.sqldelight.db.QueryResult.Value(rows.toList())
            },
            binders = null,
        ).value

        val byName = columnInfo.associateBy { it.first }
        val anchor = byName["imported_original_json"]
        assertTrue(anchor != null, "4.sqm must add the imported_original_json column. Columns present: ${byName.keys}")
        assertEquals("TEXT", anchor.second, "imported_original_json must be TEXT (was ${anchor.second})")
        assertEquals(1L, anchor.third, "imported_original_json must be NOT NULL (PRAGMA returned ${anchor.third})")

        // Smoke check: every column from the v3 baseline survives.
        for (legacyColumn in listOf("id", "friendly_name", "format", "original_json", "metadata_json", "imported_at", "last_opened_at", "last_run_prompt_id")) {
            assertTrue(legacyColumn in byName, "Pre-migration column '$legacyColumn' must survive the rebuild.")
        }
    }

    @Test
    fun m4_2_backfillsImportedOriginalJsonFromOriginalJson_forEveryRow() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, workflowV3CreateTable, 0)
        driver.execute(null, workflowV3IndexCreate, 0)

        data class V3Row(
            val id: String,
            val originalJson: String,
        )
        val seedRows = listOf(
            V3Row(id = "small", originalJson = "{}"),
            V3Row(id = "medium", originalJson = """{"nodes":[],"links":[],"viewport":{"offset":[10,20]}}"""),
            V3Row(
                id = "unicode-and-large",
                // 1 KiB-ish payload with unicode + escaped quotes — proves
                // the backfill SELECT … SELECT preserves byte content.
                originalJson = """{"nodes":[{"id":1,"type":"测试节点 ✨","note":"包含 \"双引号\" 和换行\n"}],""" +
                    """"links":[],"comment":"${"a".repeat(900)}"}""",
            ),
        )
        for (row in seedRows) {
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO workflow(
                        id, friendly_name, format,
                        original_json, metadata_json,
                        imported_at, last_opened_at, last_run_prompt_id
                    ) VALUES (?, 'seed', 'UI', ?, '{}', 0, NULL, NULL);
                """.trimIndent(),
                parameters = 2,
                binders = {
                    bindString(0, row.id)
                    bindString(1, row.originalJson)
                },
            )
        }

        ComfyMobileDb.Schema.migrate(driver, 4, 5)

        // Pull both JSON columns and assert they match byte-for-byte
        // for every row, and that no row was lost / duplicated.
        val rows = driver.executeQuery(
            identifier = null,
            sql = "SELECT id, original_json, imported_original_json FROM workflow ORDER BY id ASC;",
            parameters = 0,
            mapper = { cursor ->
                val out = mutableListOf<Triple<String, String, String>>()
                while (cursor.next().value) {
                    val id = cursor.getString(0) ?: ""
                    val original = cursor.getString(1) ?: ""
                    val imported = cursor.getString(2) ?: ""
                    out += Triple(id, original, imported)
                }
                app.cash.sqldelight.db.QueryResult.Value(out.toList())
            },
            binders = null,
        ).value

        assertEquals(seedRows.size, rows.size, "Row count must be preserved across migration (no duplication / loss).")
        val seedById = seedRows.associateBy { it.id }
        for ((id, original, imported) in rows) {
            assertTrue(id in seedById, "Unexpected row id surfaced post-migration: '$id'")
            assertEquals(seedById.getValue(id).originalJson, original, "original_json must be preserved verbatim for row '$id'.")
            assertEquals(original, imported, "imported_original_json must be byte-identical to original_json after 4.sqm backfill for row '$id'.")
        }
    }

    @Test
    fun m4_3_freshRowRoundTripsBothJsonColumns_atHeadSchema() {
        // Start from the head schema (which already includes 4.sqm
        // since Schema.create installs head, not the migration chain).
        val (driver, db) = newInMemoryComfyMobileDb()
        try {
            val rawJson = """{"nodes":[{"id":1,"type":"KSampler"}],"links":[]}"""
            db.workflowQueries.upsertWorkflow(
                id = "fresh-1",
                friendly_name = "fresh",
                format = "UI",
                original_json = rawJson,
                imported_original_json = rawJson, // importer's call shape
                metadata_json = "{}",
                imported_at = 0L,
                last_opened_at = null,
                last_run_prompt_id = null,
            )

            val row = db.workflowQueries.selectWorkflowById("fresh-1").executeAsOne()
            assertEquals(rawJson, row.original_json)
            assertEquals(rawJson, row.imported_original_json)
            assertEquals(row.original_json, row.imported_original_json, "Fresh-import contract: both JSON columns must populate from the same parsed source.")
        } finally {
            driver.close()
        }
    }
}
