/**
 * AWS adapter — per-container in-memory LRU cache.
 *
 * Lambda keeps the module scope alive between warm invocations, so a
 * module-level singleton gives free caching with no ElastiCache/VPC. Entries
 * carry their own expiry; a miss is always harmless (see Cache port).
 */
import type { Cache } from "../../core/ports.js";

interface Entry {
  value: unknown;
  expiresAt: number;
}

export function createMemoryCache(max = 5000): Cache {
  const map = new Map<string, Entry>();

  function touch(key: string, entry: Entry): void {
    // Re-insert to move to the "most recently used" end of the Map.
    map.delete(key);
    map.set(key, entry);
  }

  function evictIfNeeded(): void {
    while (map.size > max) {
      const oldest = map.keys().next();
      if (oldest.done) break;
      map.delete(oldest.value);
    }
  }

  return {
    async get<T>(key: string): Promise<T | undefined> {
      const entry = map.get(key);
      if (!entry) return undefined;
      if (entry.expiresAt <= Date.now()) {
        map.delete(key);
        return undefined;
      }
      touch(key, entry);
      return entry.value as T;
    },
    async set<T>(key: string, value: T, ttlMs: number): Promise<void> {
      if (!(ttlMs > 0)) {
        map.delete(key);
        return;
      }
      map.delete(key);
      map.set(key, { value, expiresAt: Date.now() + ttlMs });
      evictIfNeeded();
    },
    async del(key: string): Promise<void> {
      map.delete(key);
    },
  };
}

let singleton: Cache | undefined;

/** The one cache instance for this Lambda container. */
export function getContainerCache(): Cache {
  if (!singleton) singleton = createMemoryCache();
  return singleton;
}
