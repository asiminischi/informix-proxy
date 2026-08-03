import { describe, test, expect, vi } from 'vitest';
import * as grpc from '@grpc/grpc-js';
import { InformixClient } from './index';

// These tests reach into InformixClient's private `client` (the generated
// gRPC stub) and `connectionId` fields via `as any`. That's intentional: the
// class has no public seam for injecting a fake stub, and adding one just
// for tests would be a public-API change the real callers don't need.
function withMockedStub(stub: Record<string, any>): InformixClient {
  const instance = new InformixClient('localhost', 50051);
  (instance as any).client = stub;
  return instance;
}

function setConnected(instance: InformixClient, connectionId: string): void {
  (instance as any).connectionId = connectionId;
}

function serviceError(code: grpc.status): grpc.ServiceError {
  const error = new Error(`grpc error ${code}`) as grpc.ServiceError;
  error.code = code;
  error.details = 'mock';
  error.metadata = new grpc.Metadata();
  return error;
}

describe('resetConnection', () => {
  test('releases the stale connection id via Disconnect and nulls it synchronously', () => {
    const Disconnect = vi.fn();
    const client = withMockedStub({ Disconnect });
    setConnected(client, 'conn-1');

    client.resetConnection('test');

    expect(client.isConnected).toBe(false);
    expect(Disconnect).toHaveBeenCalledTimes(1);
    expect(Disconnect.mock.calls[0]![0]).toEqual({ connection_id: 'conn-1' });
  });

  test('is a no-op when there is no active connection', () => {
    const Disconnect = vi.fn();
    const client = withMockedStub({ Disconnect });

    client.resetConnection('test');

    expect(Disconnect).not.toHaveBeenCalled();
  });
});

describe('disconnect', () => {
  test('nulls connectionId even when the Disconnect RPC errors', async () => {
    const Disconnect = vi.fn((_req, cb) => cb(serviceError(grpc.status.UNAVAILABLE)));
    const client = withMockedStub({ Disconnect });
    setConnected(client, 'conn-1');

    await expect(client.disconnect()).rejects.toThrow();
    expect(client.isConnected).toBe(false);
  });

  test('resolves and clears connectionId on success', async () => {
    const Disconnect = vi.fn((_req, cb) => cb(null));
    const client = withMockedStub({ Disconnect });
    setConnected(client, 'conn-1');

    await expect(client.disconnect()).resolves.toBeUndefined();
    expect(client.isConnected).toBe(false);
  });
});

describe('self-heal on transient errors', () => {
  test('execute() resets the connection on UNAVAILABLE', async () => {
    const Disconnect = vi.fn();
    const ExecuteUpdate = vi.fn((_req, cb) => cb(serviceError(grpc.status.UNAVAILABLE)));
    const client = withMockedStub({ Disconnect, ExecuteUpdate });
    setConnected(client, 'conn-1');

    await expect(client.execute('DELETE FROM t')).rejects.toThrow();
    expect(client.isConnected).toBe(false);
    expect(Disconnect).toHaveBeenCalledTimes(1);
  });

  test('execute() resets the connection on INTERNAL', async () => {
    const Disconnect = vi.fn();
    const ExecuteUpdate = vi.fn((_req, cb) => cb(serviceError(grpc.status.INTERNAL)));
    const client = withMockedStub({ Disconnect, ExecuteUpdate });
    setConnected(client, 'conn-1');

    await expect(client.execute('DELETE FROM t')).rejects.toThrow();
    expect(client.isConnected).toBe(false);
  });

  test('execute() does NOT reset the connection on INVALID_ARGUMENT', async () => {
    const Disconnect = vi.fn();
    const ExecuteUpdate = vi.fn((_req, cb) => cb(serviceError(grpc.status.INVALID_ARGUMENT)));
    const client = withMockedStub({ Disconnect, ExecuteUpdate });
    setConnected(client, 'conn-1');

    await expect(client.execute('DELETE FROM t')).rejects.toThrow();
    expect(client.isConnected).toBe(true);
    expect(Disconnect).not.toHaveBeenCalled();
  });

  test('ping() resets the connection on UNAVAILABLE (previously had no self-heal at all)', async () => {
    const Disconnect = vi.fn();
    const Ping = vi.fn((_req, cb) => cb(serviceError(grpc.status.UNAVAILABLE)));
    const client = withMockedStub({ Disconnect, Ping });
    setConnected(client, 'conn-1');

    await expect(client.ping()).rejects.toThrow();
    expect(client.isConnected).toBe(false);
  });

  test('commit() resets the connection on INTERNAL (previously had no self-heal at all)', async () => {
    const Disconnect = vi.fn();
    const Commit = vi.fn((_req, cb) => cb(serviceError(grpc.status.INTERNAL)));
    const client = withMockedStub({ Disconnect, Commit });
    setConnected(client, 'conn-1');

    await expect(client.commit()).rejects.toThrow();
    expect(client.isConnected).toBe(false);
  });

  test('executePrepared() resets on UNAVAILABLE via the streaming call error event', async () => {
    const Disconnect = vi.fn();
    const handlers: Record<string, (...args: any[]) => void> = {};
    const call = {
      on: vi.fn((event: string, handler: (...args: any[]) => void) => {
        handlers[event] = handler;
        return call;
      }),
    };
    const ExecutePrepared = vi.fn(() => call);
    const client = withMockedStub({ Disconnect, ExecutePrepared });
    setConnected(client, 'conn-1');

    const promise = client.executePrepared('stmt-1');
    handlers.error!(serviceError(grpc.status.UNAVAILABLE));

    await expect(promise).rejects.toThrow();
    expect(client.isConnected).toBe(false);
    expect(Disconnect).toHaveBeenCalledTimes(1);
  });
});

describe('connect() concurrent-overwrite guard', () => {
  test('releases a still-active previous connection before adopting a new one', async () => {
    const Disconnect = vi.fn();
    const Connect = vi.fn((_req, _opts, cb) =>
      cb(null, { success: true, connection_id: 'conn-2', server_version: 'v1' }),
    );
    const client = withMockedStub({ Disconnect, Connect });
    setConnected(client, 'conn-1');

    const info = await client.connect({
      host: 'h',
      port: 1,
      database: 'd',
      username: 'u',
      password: 'p',
    });

    expect(info.connectionId).toBe('conn-2');
    expect(client.activeConnectionId).toBe('conn-2');
    expect(Disconnect).toHaveBeenCalledTimes(1);
    expect(Disconnect.mock.calls[0]![0]).toEqual({ connection_id: 'conn-1' });
  });
});
