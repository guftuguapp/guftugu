/**
 * Push events to live connections. Fan-out is best-effort: it never throws,
 * and only failure *counts* are logged (never payloads).
 */
import type { ServerEvent } from "../../protocol/types.js";
import { CACHE_TTL, getDevice } from "../cached.js";
import type { ConnectionRecord, Ports } from "../ports.js";

/** Live connections of a user, cached for a few seconds. */
export async function listConnections(ports: Ports, userId: string): Promise<ConnectionRecord[]> {
  const key = `conns:${userId}`;
  const hit = await ports.cache.get<ConnectionRecord[]>(key);
  if (hit) return hit;
  const conns = await ports.db.connections.listByUser(userId);
  await ports.cache.set(key, conns, CACHE_TTL.connectionsMs);
  return conns;
}

export async function invalidateConnections(ports: Ports, userId: string): Promise<void> {
  await ports.cache.del(`conns:${userId}`);
}

/** Send one event to one connection; a "gone" connection is forgotten. Never throws. */
export async function sendToConnection(ports: Ports, conn: ConnectionRecord, event: ServerEvent): Promise<boolean> {
  try {
    const result = await ports.realtime.send(conn.connectionId, event);
    if (result === "gone") {
      await ports.db.connections.remove(conn.connectionId);
      await invalidateConnections(ports, conn.userId);
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

async function sendAll(ports: Ports, conns: ConnectionRecord[], event: ServerEvent): Promise<void> {
  let failed = 0;
  for (const conn of conns) {
    if (!(await sendToConnection(ports, conn, event))) failed++;
  }
  if (failed > 0) console.warn(`fan-out ${event.type}: ${failed}/${conns.length} sends failed`);
}

/** Fan out to every live connection of each user (duplicates ignored). */
export async function sendToUsers(ports: Ports, userIds: Iterable<string>, event: ServerEvent): Promise<void> {
  try {
    const conns: ConnectionRecord[] = [];
    for (const userId of new Set(userIds)) conns.push(...(await listConnections(ports, userId)));
    await sendAll(ports, conns, event);
  } catch {
    console.warn(`fan-out ${event.type}: failed to list connections`);
  }
}

/** Fan out to every live connection of each device (duplicates ignored). */
export async function sendToDevices(ports: Ports, deviceIds: Iterable<string>, event: ServerEvent): Promise<void> {
  try {
    const wanted = new Set(deviceIds);
    const userIds = new Set<string>();
    for (const deviceId of wanted) {
      const device = await getDevice(ports, deviceId);
      if (device) userIds.add(device.userId);
    }
    const conns: ConnectionRecord[] = [];
    for (const userId of userIds) {
      for (const conn of await listConnections(ports, userId)) {
        if (wanted.has(conn.deviceId)) conns.push(conn);
      }
    }
    await sendAll(ports, conns, event);
  } catch {
    console.warn(`fan-out ${event.type}: failed to list connections`);
  }
}
