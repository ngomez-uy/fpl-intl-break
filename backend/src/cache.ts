import { AsyncLocalStorage } from "node:async_hooks";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const CACHE_DIR = path.resolve(import.meta.dirname, "../cache");

interface Entry<T> {
  savedAt: number;
  ttlMs: number | null; // null = never expires
  value: T;
}

function fileFor(key: string) {
  return path.join(CACHE_DIR, key.replace(/[^a-zA-Z0-9_-]/g, "_") + ".json");
}

// Per request: the oldest cache entry each source ("fpl", "fotmob", …) served, so the
// report can say how fresh its data is. Long-lived entries (finished matches, search
// results) don't go stale in a way that matters, so only short-lived ones count.
const oldestBySource = new AsyncLocalStorage<Map<string, number>>();
const VOLATILE_TTL_MS = 3_600_000;

function noteAge(key: string, entry: Entry<unknown>) {
  const ages = oldestBySource.getStore();
  if (!ages || entry.ttlMs === null || entry.ttlMs > VOLATILE_TTL_MS) return;
  const savedAt = entry.savedAt;
  const source = key.split("_")[0];
  ages.set(source, Math.min(ages.get(source) ?? Infinity, savedAt));
}

/** Runs `fn` and also returns, per source, when its oldest data used was fetched. */
export async function withDataAges<T>(fn: () => Promise<T>): Promise<[T, Map<string, number>]> {
  const ages = new Map<string, number>();
  const value = await oldestBySource.run(ages, fn);
  return [value, ages];
}

export async function readCache<T>(key: string): Promise<T | undefined> {
  try {
    const entry = JSON.parse(await readFile(fileFor(key), "utf8")) as Entry<T>;
    if (entry.ttlMs !== null && Date.now() - entry.savedAt > entry.ttlMs) return undefined;
    noteAge(key, entry);
    return entry.value;
  } catch {
    return undefined;
  }
}

export async function writeCache<T>(key: string, value: T, ttlMs: number | null) {
  await mkdir(CACHE_DIR, { recursive: true });
  const entry: Entry<T> = { savedAt: Date.now(), ttlMs, value };
  noteAge(key, entry);
  await writeFile(fileFor(key), JSON.stringify(entry));
}

/** Returns the cached value for `key`, or runs `load` and caches its result. */
export async function cached<T>(
  key: string,
  ttlMs: number | null | ((value: T) => number | null),
  load: () => Promise<T>,
): Promise<T> {
  const hit = await readCache<T>(key);
  if (hit !== undefined) return hit;
  const value = await load();
  await writeCache(key, value, typeof ttlMs === "function" ? ttlMs(value) : ttlMs);
  return value;
}

export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;
