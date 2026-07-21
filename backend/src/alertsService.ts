// Validation/shaping layer between the /alerts routes (devServer.ts) and
// alertStore.ts - same role httpHandler.ts plays for the read endpoints:
// routes stay dumb, malformed input becomes BadRequestError (400), and
// only clean, typed values reach storage.
import { BadRequestError } from "./httpHandler";
import {
  upsertDevice,
  setGameSub,
  type AlertDelivery,
  type AlertTierThreshold,
} from "./alertStore";

const VALID_LEAGUES = new Set(["nba", "wnba", "mlb", "nfl", "nhl"]);
const VALID_DELIVERY = new Set<AlertDelivery>(["push", "in_app", "both"]);
const VALID_TIERS = new Set<AlertTierThreshold>(["solid", "worth_your_time", "instant_classic"]);

// A client-generated UUID string. Bounded + shape-checked only (not parsed) -
// it's an opaque identity, but garbage/oversized input shouldn't reach the DB.
function requireDeviceId(value: unknown): string {
  if (typeof value !== "string" || value.length < 8 || value.length > 64) {
    throw new BadRequestError("deviceId must be an 8-64 char string");
  }
  return value;
}

/** POST /alerts/register body -> alertStore.upsertDevice. Full-state upsert, see alertStore's doc comment. */
export function registerAlertDevice(body: unknown): void {
  if (typeof body !== "object" || body === null) throw new BadRequestError("body must be a JSON object");
  const b = body as Record<string, unknown>;

  const deviceId = requireDeviceId(b.deviceId);

  const fcmToken =
    b.fcmToken === null || b.fcmToken === undefined
      ? null
      : typeof b.fcmToken === "string" && b.fcmToken.length > 0 && b.fcmToken.length <= 4096
        ? b.fcmToken
        : (() => {
            throw new BadRequestError("fcmToken must be null or a non-empty string");
          })();

  const delivery = (b.delivery ?? "both") as AlertDelivery;
  if (!VALID_DELIVERY.has(delivery)) throw new BadRequestError(`delivery must be one of: ${[...VALID_DELIVERY].join(", ")}`);

  const tierThreshold = (b.tierThreshold ?? null) as AlertTierThreshold | null;
  if (tierThreshold !== null && !VALID_TIERS.has(tierThreshold)) {
    throw new BadRequestError(`tierThreshold must be null or one of: ${[...VALID_TIERS].join(", ")}`);
  }

  const leaguesRaw = Array.isArray(b.leagues) ? b.leagues : [];
  const leagues = leaguesRaw.filter((l): l is string => typeof l === "string" && VALID_LEAGUES.has(l));

  const favoritesRaw = Array.isArray(b.favorites) ? b.favorites : [];
  // Snapshot is bounded client-side by the per-league favorites cap, but
  // never trust that: cap server-side too so a malformed client can't grow
  // rows without limit.
  if (favoritesRaw.length > 100) throw new BadRequestError("too many favorites");
  const favorites = favoritesRaw.map((f) => {
    const fav = f as Record<string, unknown>;
    if (typeof fav.teamName !== "string" || fav.teamName.length === 0 || fav.teamName.length > 100) {
      throw new BadRequestError("each favorite needs a teamName string");
    }
    const leagueGroup = typeof fav.leagueGroup === "string" && VALID_LEAGUES.has(fav.leagueGroup) ? fav.leagueGroup : null;
    return { teamName: fav.teamName, leagueGroup };
  });

  upsertDevice({
    deviceId,
    fcmToken,
    closeSwingEnabled: b.closeSwingEnabled !== false,
    delivery,
    favoritesOnly: b.favoritesOnly !== false,
    tierThreshold,
    leagues,
    favorites,
  });
}

/** POST /alerts/game-sub body -> alertStore.setGameSub (the per-game bell). */
export function setAlertGameSub(body: unknown): void {
  if (typeof body !== "object" || body === null) throw new BadRequestError("body must be a JSON object");
  const b = body as Record<string, unknown>;
  const deviceId = requireDeviceId(b.deviceId);
  if (typeof b.eventId !== "string" || b.eventId.length === 0 || b.eventId.length > 32) {
    throw new BadRequestError("eventId must be a non-empty string");
  }
  if (typeof b.subscribed !== "boolean") throw new BadRequestError("subscribed must be a boolean");
  setGameSub(deviceId, b.eventId, b.subscribed);
}
