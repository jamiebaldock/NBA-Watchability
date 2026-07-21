// Thin wrapper around firebase-admin's messaging - the only file that
// touches the Firebase SDK, so the rest of the backend deals in plain
// "send this title/body/data to these tokens" terms.
//
// Credentials come from the FIREBASE_SERVICE_ACCOUNT env var (the service
// account key JSON, pasted verbatim into Render's env settings - same
// pattern as every other secret in this deployment). Deliberately degrades
// to a warn-once no-op when unset: the backend must keep working fully
// (including locally and on Render before James finishes the Firebase
// console setup) with alerts simply inert, not crash-looping on a missing
// secret.
import { getMessaging, type Messaging } from "firebase-admin/messaging";
import { initializeApp, cert } from "firebase-admin/app";

let messaging: Messaging | null | undefined;

function getFcm(): Messaging | null {
  if (messaging !== undefined) return messaging;
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) {
    console.warn("[fcm] FIREBASE_SERVICE_ACCOUNT not set - push sends are no-ops until it is");
    messaging = null;
    return messaging;
  }
  try {
    const serviceAccount = JSON.parse(raw);
    initializeApp({ credential: cert(serviceAccount) });
    messaging = getMessaging();
  } catch (err) {
    console.error("[fcm] failed to initialize firebase-admin - push sends disabled", err);
    messaging = null;
  }
  return messaging;
}

export function isFcmConfigured(): boolean {
  return getFcm() !== null;
}

export interface PushResult {
  token: string;
  ok: boolean;
  // FCM's error code when not ok - "messaging/registration-token-not-registered"
  // is the one callers act on (prune the token via alertStore.clearDeadToken).
  errorCode?: string;
}

export const DEAD_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/**
 * Sends one alert to many tokens. Data-only, high-priority message
 * (delivers through Doze; the client's FirebaseMessagingService decides
 * native-notification vs in-app banner from the device's own delivery
 * pref, which a notification-payload message would bypass). Per-token
 * results are returned rather than throwing, so one dead token never
 * blocks the rest of a fan-out.
 */
export async function sendPush(
  tokens: string[],
  data: Record<string, string>
): Promise<PushResult[]> {
  const fcm = getFcm();
  if (!fcm || tokens.length === 0) return tokens.map((token) => ({ token, ok: false, errorCode: "not-configured" }));

  const response = await fcm.sendEachForMulticast({
    tokens,
    data,
    android: { priority: "high" },
  });
  return response.responses.map((r, i) => ({
    token: tokens[i],
    ok: r.success,
    errorCode: r.error?.code,
  }));
}
