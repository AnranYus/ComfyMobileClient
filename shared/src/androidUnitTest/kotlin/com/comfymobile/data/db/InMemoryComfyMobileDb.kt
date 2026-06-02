package com.comfymobile.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.comfymobile.db.ComfyMobileDb

/**
 * JVM-on-Android in-memory `ComfyMobileDb` factory for `androidUnitTest`.
 *
 * Per ADR-0006 §6a: SQLDelight migration semantics under SQLite are
 * portable across drivers; this is the unit-test execution path. The
 * `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` dependency is JVM-only
 * and is deliberately scoped to the `androidUnitTest` source-set so a
 * future `iosTest` compile path does not pick it up.
 *
 * Returns the driver alongside the database so tests that need to
 * stage raw SQL (for example to seed v3-shape rows before
 * `Schema.migrate(driver, 3, 4)`) can use the same connection.
 */
internal data class InMemoryDb(
    val driver: SqlDriver,
    val db: ComfyMobileDb,
)

/**
 * Construct a fresh in-memory `ComfyMobileDb` at the current head
 * schema (post-4.sqm). Tests that exercise `Migration4Test` step 3 (an
 * `upsertWorkflow` post-migration) call this; tests that need to start
 * at v3 should use [newRawInMemoryDriverAtV3] instead and apply the
 * migration explicitly.
 */
internal fun newInMemoryComfyMobileDb(): InMemoryDb {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ComfyMobileDb.Schema.create(driver)
    return InMemoryDb(driver = driver, db = ComfyMobileDb(driver))
}
