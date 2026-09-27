const UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";

// Keep request bursts small: these are free, unofficial endpoints.
const MAX_CONCURRENT = 4;
let active = 0;
const queue: (() => void)[] = [];

async function acquire() {
  if (active < MAX_CONCURRENT) {
    active++;
    return;
  }
  await new Promise<void>((resolve) => queue.push(resolve));
  active++;
}

function release() {
  active--;
  queue.shift()?.();
}

export async function getJson<T>(url: string): Promise<T> {
  await acquire();
  try {
    for (let attempt = 1; ; attempt++) {
      const res = await fetch(url, { headers: { "User-Agent": UA, Accept: "application/json" } });
      if (res.ok) return (await res.json()) as T;
      if (attempt >= 3 || (res.status < 500 && res.status !== 429)) {
        throw new Error(`GET ${url} -> ${res.status}`);
      }
      await new Promise((r) => setTimeout(r, 500 * attempt));
    }
  } finally {
    release();
  }
}
