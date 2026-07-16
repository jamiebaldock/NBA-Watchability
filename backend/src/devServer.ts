import compression from "compression";
import express from "express";
import {
  BadRequestError,
  getCurrentSeasonStartForLeagueGroup,
  getHistoryForRange,
  getNewsForLeagueGroup,
  getNextGameDateForLeagueGroup,
  getSchedule,
  getSeasonWindowForLeagueGroup,
  getStandingsForLeagueGroup,
  getStatsForLeagueGroup,
} from "./httpHandler";
import { startHighlightsPoller } from "./highlightsPoller";
import { applySeedHighlights } from "./highlightsSeed";
import { migrateHistoricalBackfill } from "./migrateToGameStore";
import { getRawLagSamples } from "./gameStore";
import { fetchPublishedAtTimes } from "./youtubeClient";

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

// Temporary read-only diagnostic - investigating whether the learned
// highlights lag reflects true YouTube upload time or is inflated by our
// own check-schedule (isDueForHighlightsCheck gates check 0 behind the
// currently-learned p50, which can only ever push the aggregate up or hold
// it flat, never down). Cross-checks each matched video's real
// snippet.publishedAt against our own yt_found_at. Removed once resolved.
app.get("/admin/lag-samples", async (req, res) => {
  try {
    const leagueGroup = String(req.query.leagueGroup ?? "nba");
    const samples = getRawLagSamples(leagueGroup);
    const published = await fetchPublishedAtTimes(samples.map((s) => s.yt_video_id));
    const result = samples.map((s) => {
      const ourLagMs = new Date(s.yt_found_at).getTime() - new Date(s.final_at).getTime();
      const publishedAt = published.get(s.yt_video_id) ?? null;
      const trueLagMs = publishedAt ? new Date(publishedAt).getTime() - new Date(s.final_at).getTime() : null;
      return {
        event: `${s.away} @ ${s.home}`,
        league: s.league,
        final_at: s.final_at,
        yt_found_at: s.yt_found_at,
        our_lag_min: Math.round(ourLagMs / 60000),
        yt_check_count: s.yt_check_count,
        video_published_at: publishedAt,
        true_lag_min: trueLagMs !== null ? Math.round(trueLagMs / 60000) : null,
      };
    });
    res.json(result);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: "internal error" });
  }
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
