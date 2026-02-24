#!/usr/bin/env node
/**
 * Informix gRPC Proxy — Integration Test Client
 *
 * Reads connection details from .env (dotenv) and exercises every major
 * RPC exposed by the proxy: Connect, Ping, ExecuteQuery, ExecuteUpdate,
 * BeginTransaction / Commit / Rollback, GetMetadata, and Disconnect.
 *
 * Usage:
 *   npm install          # first time
 *   node test-client.js  # run all tests
 *
 * Exit codes:
 *   0 — all tests passed
 *   1 — one or more tests failed
 */

'use strict';

require('dotenv').config();           // loads ./clients/nodejs/.env
const InformixClient = require('./informix-client');

// ─── Configuration from environment ────────────────────────────────────────
const CONFIG = Object.freeze({
    proxy: {
        host: process.env.PROXY_HOST || 'localhost',
        port: parseInt(process.env.PROXY_PORT, 10) || 50051,
    },
    informix: {
        host:     process.env.INFORMIX_HOST     || 'informix-db',
        port:     parseInt(process.env.INFORMIX_PORT, 10) || 9088,
        server:   process.env.INFORMIX_SERVER   || 'informix',
        database: process.env.INFORMIX_DB       || 'testdb',
        username: process.env.INFORMIX_USER     || 'informix',
        password: process.env.INFORMIX_PASSWORD || 'in4mix',
        poolSize: parseInt(process.env.POOL_SIZE, 10) || 10,
    },
});

// ─── Pretty logging helpers ────────────────────────────────────────────────
const PASS = '\x1b[32mPASS\x1b[0m';
const FAIL = '\x1b[31mFAIL\x1b[0m';
const INFO = '\x1b[36mINFO\x1b[0m';

let passed = 0;
let failed = 0;

function logResult(label, ok, detail = '') {
    const tag = ok ? PASS : FAIL;
    console.log(`  [${tag}] ${label}${detail ? '  — ' + detail : ''}`);
    if (ok) passed++; else failed++;
}

// ─── Individual test cases ─────────────────────────────────────────────────

async function testConnect(client) {
    const info = await client.connect(CONFIG.informix);
    const ok = !!info.connectionId;
    logResult('Connect', ok, `id=${info.connectionId}  version=${info.serverVersion}`);
    return ok;
}

async function testPing(client) {
    const pong = await client.ping();
    logResult('Ping', pong.alive, `latency=${pong.latencyMs}ms`);
    return pong.alive;
}

async function testSimpleQuery(client) {
    const result = await client.query('SELECT FIRST 5 * FROM systables', []);
    const ok = result.rows.length > 0;
    logResult('Simple query (systables)', ok, `${result.rows.length} rows returned`);
    return ok;
}

async function testParameterisedQuery(client) {
    const result = await client.query(
        'SELECT tabname FROM systables WHERE tabid > ?', [99]
    );
    const ok = Array.isArray(result.rows);
    logResult('Parameterised query', ok, `${result.rows.length} rows`);
    return ok;
}

async function testStreamQuery(client) {
    let count = 0;
    await client.queryStream(
        'SELECT FIRST 10 tabname FROM systables', [],
        (_row) => { count++; },
        { fetchSize: 5 }
    );
    const ok = count > 0;
    logResult('Streaming query', ok, `${count} rows streamed`);
    return ok;
}

async function testExecuteUpdate(client) {
    // Wrap in a transaction so all statements run on the same pooled connection
    try {
        await client.beginTransaction();
        await client.execute(
            'CREATE TEMP TABLE _test_env (id SERIAL PRIMARY KEY, val VARCHAR(50)) WITH NO LOG'
        );
        const affected = await client.execute(
            "INSERT INTO _test_env (val) VALUES (?)", ['hello']
        );
        await client.commit();
        const ok = affected >= 1;
        logResult('ExecuteUpdate (INSERT)', ok, `rows_affected=${affected}`);
        return ok;
    } catch (err) {
        logResult('ExecuteUpdate (INSERT)', false, err.message);
        try { await client.rollback(); } catch (_) { /* ignore */ }
        return false;
    }
}

async function testTransaction(client) {
    // --- Commit path ---
    try {
        await client.beginTransaction();
        await client.execute(
            'CREATE TEMP TABLE _test_txn (id SERIAL PRIMARY KEY, val VARCHAR(50)) WITH NO LOG'
        );
        await client.execute("INSERT INTO _test_txn (val) VALUES (?)", ['committed']);
        await client.commit();
        logResult('Transaction — commit', true);
    } catch (err) {
        logResult('Transaction — commit', false, err.message);
        try { await client.rollback(); } catch (_) { /* ignore */ }
    }

    // --- Rollback path ---
    try {
        await client.beginTransaction();
        await client.execute(
            'CREATE TEMP TABLE _test_rb (id SERIAL PRIMARY KEY, val VARCHAR(50)) WITH NO LOG'
        );
        await client.execute("INSERT INTO _test_rb (val) VALUES (?)", ['will-rollback']);
        await client.rollback();
        logResult('Transaction — rollback', true);
    } catch (err) {
        logResult('Transaction — rollback', false, err.message);
        try { await client.rollback(); } catch (_) { /* ignore */ }
    }

    return true;
}

async function testGetMetadata(client) {
    try {
        const tables = await client.getMetadata();
        const ok = Array.isArray(tables) && tables.length > 0;
        logResult('GetMetadata', ok, `${tables.length} tables`);
        return ok;
    } catch (err) {
        logResult('GetMetadata', false, err.message);
        return false;
    }
}

async function testDisconnect(client) {
    await client.disconnect();
    logResult('Disconnect', true);
    return true;
}

// ─── Runner ────────────────────────────────────────────────────────────────
async function main() {
    console.log();
    console.log(`[${INFO}] Informix gRPC Proxy — Integration Tests`);
    console.log(`[${INFO}] Proxy   : ${CONFIG.proxy.host}:${CONFIG.proxy.port}`);
    console.log(`[${INFO}] DB Host : ${CONFIG.informix.host}:${CONFIG.informix.port}`);
    console.log(`[${INFO}] Database: ${CONFIG.informix.database}`);
    console.log(`[${INFO}] User    : ${CONFIG.informix.username}`);
    console.log();

    const client = new InformixClient(CONFIG.proxy.host, CONFIG.proxy.port);

    try {
        if (!await testConnect(client))   { throw new Error('Cannot proceed without connection'); }
        await testPing(client);
        await testSimpleQuery(client);
        await testParameterisedQuery(client);
        await testStreamQuery(client);
        await testExecuteUpdate(client);
        await testTransaction(client);
        await testGetMetadata(client);
        await testDisconnect(client);
    } catch (err) {
        console.error(`\n  [${FAIL}] Fatal: ${err.message}\n`);
        failed++;
        try { await client.disconnect(); } catch (_) { /* ignore */ }
    }

    console.log();
    console.log(`  ── Results: ${passed} passed, ${failed} failed ──`);
    console.log();

    process.exit(failed > 0 ? 1 : 0);
}

main();
