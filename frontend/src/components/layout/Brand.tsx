/** Leash mark: a looped handle and a taut lead ending at the collar, on a burgundy tile. Original artwork. */
export function BrandMark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <defs>
        <linearGradient id="leash-tile" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#b8224f" />
          <stop offset="1" stopColor="#5c0f26" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#leash-tile)" />
      <circle cx="10.5" cy="21.5" r="4.2" fill="none" stroke="#fff" strokeWidth="2.6" />
      <path d="M13.5 18.5 C17 15 18.5 15.5 21 12.5" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" />
      <circle cx="22.8" cy="10.2" r="2.8" fill="#fff" />
    </svg>
  );
}
