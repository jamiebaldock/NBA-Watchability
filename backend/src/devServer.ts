import compression from "compression";
import express from "express";
import {
  BadRequestError,
  getNewsForLeagueGroup,
  getSchedule,
  getStandingsForLeagueGroup,
  getStatsForLeagueGroup,
} from "./httpHandler";

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

app.listen(PORT, () => {
  console.log(`NBA Watchability backend dev server listening on http://localhost:${PORT}`);
  console.log(`Try: http://localhost:${PORT}/schedule?start=2025-01-15&end=2025-01-15`);
});
