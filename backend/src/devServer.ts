import express from "express";
import { BadRequestError, getSchedule } from "./httpHandler";

const app = express();
const PORT = process.env.PORT ? Number(process.env.PORT) : 8787;

app.get("/schedule", async (req, res) => {
  try {
    const start = String(req.query.start ?? "");
    const end = String(req.query.end ?? "");
    const schedule = await getSchedule(start, end);
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

app.listen(PORT, () => {
  console.log(`NBA Watchability backend dev server listening on http://localhost:${PORT}`);
  console.log(`Try: http://localhost:${PORT}/schedule?start=2025-01-15&end=2025-01-15`);
});
