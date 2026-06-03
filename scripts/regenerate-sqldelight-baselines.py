#!/usr/bin/env python3
"""
One-off generator for SQLDelight bootstrap baseline `.db` files.

Per ADR-0006 §Consequences + task #17: SQLDelight 2.x `verifyMigrations(true)`
needs `databases/<N>.db` schema dumps as starting points when migrations are
ALTER-style (not "create from empty"). This codebase's 1/2/3.sqm are pure
ALTERs that assume jobIndex pre-existed, so verifyMigrations can't run from
empty without a v1 baseline.

This script creates 1.db ... 4.db (5.db is the head dump SQLDelight produces
itself via schemaOutputDirectory). Each file is a SQLite database whose
schema matches the state at that version:

- v1 (pre-1.sqm): jobIndex without is_favorite / workflow_id; no workflow
                  table; only the jobIndex_byServer_byCreated index.
- v2 (post-1.sqm): v1 + is_favorite column.
- v3 (post-2.sqm): v2 + workflow table + workflow_recents index.
- v4 (post-3.sqm): v3 + jobIndex.workflow_id + jobIndex_byServer_byWorkflow_byFinished index.
- v5 (post-4.sqm): committed as databases/5.db by SQLDelight; not touched here.

Re-run when a new ALTER-style migration is added (e.g. 5.sqm) — extend
the version chain below by one step and the new baseline will be emitted
alongside the existing ones. SQLDelight's own schemaOutputDirectory still
produces the head dump (currently 5.db); this script only fills the
historical baselines below head.

Run from repo root:
    python3 scripts/regenerate-sqldelight-baselines.py
Output: shared/src/commonMain/sqldelight/databases/{1,2,3,4}.db
"""
import os
import sqlite3
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(REPO_ROOT, "shared/src/commonMain/sqldelight/databases")

V1_JOBINDEX = """
CREATE TABLE jobIndex (
    prompt_id TEXT NOT NULL PRIMARY KEY,
    server_id TEXT NOT NULL,
    status TEXT NOT NULL,
    workflow_snapshot_json TEXT,
    api_prompt_json TEXT,
    label TEXT,
    first_output_filename TEXT,
    first_output_subfolder TEXT,
    first_output_type TEXT,
    created_at INTEGER NOT NULL,
    finished_at INTEGER
)
"""

V1_INDEX = """
CREATE INDEX jobIndex_byServer_byCreated
    ON jobIndex(server_id, created_at DESC)
"""

MIGRATION_1_TO_2 = open(os.path.join(REPO_ROOT, "shared/src/commonMain/sqldelight/com/comfymobile/db/1.sqm")).read()
MIGRATION_2_TO_3 = open(os.path.join(REPO_ROOT, "shared/src/commonMain/sqldelight/com/comfymobile/db/2.sqm")).read()
MIGRATION_3_TO_4 = open(os.path.join(REPO_ROOT, "shared/src/commonMain/sqldelight/com/comfymobile/db/3.sqm")).read()


def write_db(version, path, setup):
    """Build a fresh SQLite DB at `path` by running `setup(conn)`."""
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    try:
        setup(conn)
        conn.commit()
    finally:
        conn.close()
    # Sanity: read back the schema names.
    conn = sqlite3.connect(path)
    try:
        names = sorted(
            (row[0], row[1]) for row in conn.execute(
                "SELECT type, name FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY type, name"
            )
        )
        print(f"  v{version} -> {path}")
        for typ, name in names:
            print(f"    {typ}: {name}")
    finally:
        conn.close()


def v1(conn):
    conn.executescript(V1_JOBINDEX + ";" + V1_INDEX + ";")


def v2(conn):
    v1(conn)
    conn.executescript(MIGRATION_1_TO_2)


def v3(conn):
    v2(conn)
    conn.executescript(MIGRATION_2_TO_3)


def v4(conn):
    v3(conn)
    conn.executescript(MIGRATION_3_TO_4)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"Writing baselines into {OUT_DIR}")
    write_db(1, os.path.join(OUT_DIR, "1.db"), v1)
    write_db(2, os.path.join(OUT_DIR, "2.db"), v2)
    write_db(3, os.path.join(OUT_DIR, "3.db"), v3)
    write_db(4, os.path.join(OUT_DIR, "4.db"), v4)
    print("Done.")


if __name__ == "__main__":
    main()
