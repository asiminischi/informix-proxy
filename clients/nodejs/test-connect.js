#!/usr/bin/env node
const InformixClient = require('./informix-client');

const PROXY_HOST = process.env.PROXY_HOST || 'localhost';
const PROXY_PORT = parseInt(process.env.PROXY_PORT || '50051', 10);

const DB_HOST = process.env.DB_HOST || 'your-informix-host';
const DB_PORT = parseInt(process.env.DB_PORT || '9088', 10);
const DB_NAME = process.env.DB_NAME || 'your_database';
const DB_USER = process.env.DB_USER || 'informix';
const DB_PASS = process.env.DB_PASS || '';
const POOL_SIZE = parseInt(process.env.POOL_SIZE || '5', 10);

async function main() {
  const client = new InformixClient(PROXY_HOST, PROXY_PORT);

  try {
    console.log('Connecting to Informix via proxy', PROXY_HOST + ':' + PROXY_PORT);
    const info = await client.connect({
      host: DB_HOST,
      port: DB_PORT,
      database: DB_NAME,
      username: DB_USER,
      password: DB_PASS,
      poolSize: POOL_SIZE,
      properties: { INFORMIXSERVER: process.env.DB_SERVER || 'informix' }
    });

    console.log('Connected. Server version:', info.serverVersion);

    const ping = await client.ping();
    console.log('Ping:', ping);

    // Simple test query (may need to adjust for your schema)
    try {
      const res = await client.query('SELECT 1 AS test_col FROM systables WHERE tabid = 1');
      console.log('Test query result rows:', res.rows.slice(0,5));
    } catch (qerr) {
      console.warn('Test query failed (this may be fine depending on schema):', qerr.message);
    }

    await client.disconnect();
    console.log('Disconnected');

  } catch (err) {
    console.error('Connection failed:', err.message || err);
    try { await client.disconnect(); } catch(_) {}
    process.exitCode = 2;
  }
}

if (require.main === module) main();
