import { describe, expect, it, vi } from "vitest";
import { onOwnerChange, ownerPassphrase, setOwnerPassphrase } from "./owner";

// Vitest runs in Node here, where sessionStorage doesn't exist: this also covers the "storage unavailable" path.
describe("owner passphrase", () => {
  it("remembers, forgets and tells listeners", () => {
    const l = vi.fn();
    const off = onOwnerChange(l);
    setOwnerPassphrase("pw");
    expect(ownerPassphrase()).toBe("pw");
    setOwnerPassphrase(null);
    expect(ownerPassphrase()).toBeNull();
    expect(l).toHaveBeenCalledTimes(2);
    off();
  });

});
