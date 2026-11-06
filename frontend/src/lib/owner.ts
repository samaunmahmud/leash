// The owner passphrase for this tab (T17 public demo). Kept in sessionStorage, so closing the tab locks owner mode
// again; storage can be missing or blocked (private windows, previews), so every access is guarded and the app
// still works, it just asks again.

const KEY = "leash.owner";
let memory: string | null = null;
const listeners = new Set<() => void>();

export function ownerPassphrase(): string | null {
  try {
    return sessionStorage.getItem(KEY) ?? memory;
  } catch {
    return memory;
  }
}

export function setOwnerPassphrase(p: string | null) {
  memory = p;
  try {
    if (p) sessionStorage.setItem(KEY, p);
    else sessionStorage.removeItem(KEY);
  } catch {
    /* memory copy is enough for this page */
  }
  listeners.forEach((l) => l());
}

export function onOwnerChange(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
