import compression from "compression";
import express from "express";
import { forceRescoreWnbaGames } from "./gameStore";
import {
  BadRequestError,
  getHistoryForRange,
  getNewsForLeagueGroup,
  getSchedule,
  getStandingsForLeagueGroup,
  getStatsForLeagueGroup,
} from "./httpHandler";
import { startHighlightsPoller } from "./highlightsPoller";
import { applySeedHighlights } from "./highlightsSeed";
import { migrateHistoricalBackfill } from "./migrateToGameStore";
import { computeWatchabilityScore, tierForScore } from "./rubric";

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

// TEMPORARY - one-off correction for WNBA games scored before the
// league-aware rubric calibration deployed (commit 87789fd and follow-ups).
// Recomputes score/tier from each game's already-stored raw rubric inputs
// (no ESPN re-fetch) and overwrites only where it actually changed. Scoped
// to WNBA only, safe to hit more than once (a no-op against already-
// corrected rows). Remove this route once run against production.
app.get("/api/admin/rescore-wnba", (_req, res) => {
  const changes = forceRescoreWnbaGames((row) => {
    const breakdown = computeWatchabilityScore(
      {
        status: row.status,
        finalMargin: row.finalMargin ?? undefined,
        largestDeficitOvercome: row.largestDeficitOvercome ?? undefined,
        leadChanges: row.leadChanges ?? undefined,
        overtimePeriods: row.overtimePeriods ?? 0,
        closeInFinalTwoMin: Boolean(row.closeInFinalTwoMin),
        leadChangeInFinalMin: Boolean(row.leadChangeInFinalMin),
        decidedOnFinalPossession: Boolean(row.decidedOnFinalPossession),
        buzzerBeater: Boolean(row.buzzerBeater),
        starPerformance: row.starPerformance,
      },
      row.stakes ?? undefined,
      "wnba"
    );
    return { score: breakdown.total, tier: tierForScore(breakdown.total) };
  });
  res.json({ changedCount: changes.length, changes });
});

app.listen(PORT, () => {
  console.log(`NBA Watchability backend dev server listening on http://localhost:${PORT}`);
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
});
