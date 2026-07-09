// Entry point for Cloud Function deployment (Google Cloud Functions /
// AWS Lambda via a thin adapter). Local dev uses devServer.ts instead.
import type { Request, Response } from "express";
import { BadRequestError, getSchedule } from "./httpHandler";

export async function nbaWatchability(req: Request, res: Response): Promise<void> {
  try {
    const start = String(req.query.start ?? "");
    const end = String(req.query.end ?? "");
    const schedule = await getSchedule(start, end);
    res.set("Cache-Control", "public, max-age=60");
    res.json({ schedule });
  } catch (err) {
    if (err instanceof BadRequestError) {
      res.status(400).json({ error: err.message });
    } else {
      console.error(err);
      res.status(500).json({ error: "internal error" });
    }
  }
}
