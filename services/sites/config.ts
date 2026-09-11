function positive(name: string, fallback: number): number {
  const raw = Deno.env.get(name);
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}

const LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

/**
 * A published site's normal state is that nobody touches it: it is a finished thing someone handed a
 * link to, not a scratch directory. So nothing expires unless an operator asks for it, and `never`
 * turns expiry back off without deleting the line.
 */
function retention(): number | null {
  const raw = Deno.env.get("SITES_RETAIN_DAYS")?.trim();
  if (!raw || raw === "never") return null;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error("SITES_RETAIN_DAYS must be a positive number of days, or `never`");
  }
  return value;
}

export function readConfig() {
  const domain = Deno.env.get("SITES_DOMAIN")?.trim().toLowerCase() || "";
  if (domain.length > 253 || !new RegExp(`^${LABEL}(?:\\.${LABEL})+$`).test(domain)) {
    throw new Error("SITES_DOMAIN must be the domain sites are served under, such as `example.com`");
  }
  const maxMb = positive("SITES_MAX_MB", 100);
  const maxFileMb = positive("SITES_MAX_FILE_MB", 25);
  if (maxFileMb > maxMb) throw new Error("SITES_MAX_FILE_MB must not exceed SITES_MAX_MB");
  return {
    domain,
    dataDir: "/data",
    token: Deno.env.get("SITES_TOKEN")?.trim() || null,
    tokenFile: Deno.env.get("SITES_TOKEN_FILE")?.trim() || null,
    maxMb,
    maxFileMb,
    maxFiles: positive("SITES_MAX_FILES", 1000),
    maxDepth: positive("SITES_MAX_DEPTH", 10),
    retainDays: retention(),
    minFreeMb: positive("SITES_MIN_FREE_MB", 2048),
    publishesPerHour: positive("SITES_PUBLISHES_PER_HOUR", 20),
    uploadIdleMinutes: positive("SITES_UPLOAD_IDLE_MINUTES", 15),
  };
}

export type Config = ReturnType<typeof readConfig>;
