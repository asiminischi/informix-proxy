import { describe, test, expect } from 'vitest';
import descriptor from './informix-descriptor.json';

/**
 * Regression test for a real bug: pbjs (without --keep-case) renames every
 * field to camelCase as its canonical name, keeping the original only as
 * unused "protoName" metadata. protoLoader.fromJSON() builds directly off
 * the canonical name, so without --keep-case in generate-proto.mjs, every
 * snake_case field access in index.ts (connection_id, rows_affected,
 * total_rows, ...) silently read undefined at runtime - keepCase in the
 * loader options never got a chance to act, since the descriptor never had
 * the snake_case name to begin with. This only showed up against a live
 * server; every mocked-stub test in index.test.ts passed regardless.
 */
describe('generated proto descriptor', () => {
  function fieldsOf(messageName: string): Record<string, unknown> {
    const message = (descriptor as any).nested.informix.nested[messageName];
    return message.fields;
  }

  test('ConnectionResponse keeps snake_case field names', () => {
    const fields = fieldsOf('ConnectionResponse');
    expect(fields).toHaveProperty('connection_id');
    expect(fields).toHaveProperty('server_version');
    expect(fields).not.toHaveProperty('connectionId');
    expect(fields).not.toHaveProperty('serverVersion');
  });

  test('QueryResponse keeps snake_case field names', () => {
    const fields = fieldsOf('QueryResponse');
    expect(fields).toHaveProperty('total_rows');
    expect(fields).toHaveProperty('has_more');
    expect(fields).not.toHaveProperty('totalRows');
  });

  test('UpdateResponse keeps snake_case field names', () => {
    const fields = fieldsOf('UpdateResponse');
    expect(fields).toHaveProperty('rows_affected');
    expect(fields).not.toHaveProperty('rowsAffected');
  });

  test('PrepareResponse keeps snake_case field names', () => {
    const fields = fieldsOf('PrepareResponse');
    expect(fields).toHaveProperty('statement_id');
    expect(fields).toHaveProperty('parameter_count');
  });
});
