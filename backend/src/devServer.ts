import compression from "compression";
import express from "express";
import {
  BadRequestError,
  getCurrentSeasonStartForLeagueGroup,
  getGameDetailForEvent,
  getHistoryForRange,
  getNewsForLeagueGroup,
  getNextGameDateForLeagueGroup,
  getRosterForTeam,
  getSchedule,
  getScheduleCountsForLeagueGroup,
  getSeasonWindowForLeagueGroup,
  getStandingsForLeagueGroup,
  getStatsForLeagueGroup,
  getTeamScheduleForLeagueGroup,
  getTeamsForLeagueGroup,
} from "./httpHandler";
import { registerAlertDevice, setAlertGameSub } from "./alertsService";
import { isFcmConfigured } from "./fcm";
import { startHighlightsPoller } from "./highlightsPoller";
import { startAlertsPoller } from "./alertsPoller";
import { applySeedHighlights } from "./highlightsSeed";
import { migrateHistoricalBackfill } from "./migrateToGameStore";

const app = express();
const PORT = process.env.PORT ? Number(process.env.PORT) : 8787;

// Free bandwidth win: gzips every response - JSON compresses very well, so
// this cuts egress substantially with zero feature/behavior change.
app.use(compression());

app.get("/schedule", async (req, res) => {
  try {
    const start = String(req.query.start ?? "");
    const end = String(req.query.end ?? "");
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    const schedule = await getSchedule(start, end, leagueGroup);
    res.json({ schedule });
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/standings", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getStandingsForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/stats", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getStatsForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/api/history", async (req, res) => {
  try {
    const start = String(req.query.start ?? "");
    const end = String(req.query.end ?? "");
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getHistoryForRange(start, end, leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/next-game-date", async (req, res) => {
  try {
    const after = String(req.query.after ?? "");
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getNextGameDateForLeagueGroup(after, leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/schedule-counts", async (req, res) => {
  try {
    const year = Number(req.query.year);
    const month = Number(req.query.month);
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getScheduleCountsForLeagueGroup(year, month, leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/season-window", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getSeasonWindowForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/current-season-start", (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(getCurrentSeasonStartForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/news", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getNewsForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/teams", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getTeamsForLeagueGroup(leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/roster", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    const team = String(req.query.team ?? "");
    res.json(await getRosterForTeam(leagueGroup, team));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/game-detail", async (req, res) => {
  try {
    const eventId = String(req.query.eventId ?? "");
    res.json(await getGameDetailForEvent(eventId));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.get("/team-schedule", async (req, res) => {
  try {
    const teamId = String(req.query.teamId ?? "");
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    res.json(await getTeamScheduleForLeagueGroup(teamId, leagueGroup));
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

// The Alerts feature's two write endpoints (everything else on this server
// is read-only GETs, hence express.json() scoped here rather than global -
// no GET route parses a body). Both are full-state/idempotent, see
// alertsService.ts.
app.post("/alerts/register", express.json(), (req, res) => {
  try {
    registerAlertDevice(req.body);
    res.json({ ok: true });
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

app.post("/alerts/game-sub", express.json(), (req, res) => {
  try {
    setAlertGameSub(req.body);
    res.json({ ok: true });
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
});

// Exercises fcm.ts's lazy init on demand, rather than waiting for the first
// real send (which nothing triggers yet - no detection poller exists) - the
// only way to confirm FIREBASE_SERVICE_ACCOUNT actually parsed into valid
// credentials versus just being present as an env var.
app.get("/alerts/status", (_req, res) => {
  res.json({ fcmConfigured: isFcmConfigured() });
});

app.listen(PORT, () => {
  console.log(`Big4 Watchability backend dev server listening on http://localhost:${PORT}`);
  console.log(`Try: http://localhost:${PORT}/schedule?start=2025-01-15&end=2025-01-15`);
  // migrateHistoricalBackfill is idempotent - on a fresh/empty persistent
  // disk this self-seeds the 2,650-game backfill with no manual step
  // required; against an already-populated store it's a harmless no-op
  // (just re-verifies nothing's missing).
  migrateHistoricalBackfill();
  // gameStore's guarded setters (WHERE x IS NULL) make the seed and the
  // poller safe to run in either order or even concurrently - sequencing
  // here is just to have the seed's rows exist before the poller's first
  // tick, not a correctness requirement anymore.
  applySeedHighlights().then(startHighlightsPoller);
  startAlertsPoller();
});
