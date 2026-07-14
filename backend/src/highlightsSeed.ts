// One-time manual seed: video IDs confirmed by eye (the user pasted real
// YouTube links, matched here to their exact ESPN event by team names/date)
// rather than found via search.list - lets specific games show a working
// highlights link immediately without spending any of the 100/day search
// quota. Safe to run on every startup: gameStore.setHighlights only ever
// writes when yt_video_id is still NULL, so a genuine search result already
// on file is never clobbered - the guard lives in the store itself now, not
// in this file's own bookkeeping.
import { getGamesForDate } from "./gamesService";
import { setHighlights } from "./gameStore";
import { LeagueGroup } from "./types";

interface SeedEntry {
  date: string; // YYYY-MM-DD, just to know which schedule fetch surfaces this event
  leagueGroup: LeagueGroup;
  eventId: string;
  videoId: string;
}

const SEED_ENTRIES: SeedEntry[] = [
  // WNBA
  { date: "2026-07-09", leagueGroup: "wnba", eventId: "401857051", videoId: "bvxI5XYci-c" }, // Seattle Storm at Atlanta Dream
  { date: "2026-07-09", leagueGroup: "wnba", eventId: "401857052", videoId: "HTuwygyES0I" }, // Indiana Fever at Phoenix Mercury
  { date: "2026-07-09", leagueGroup: "wnba", eventId: "401857053", videoId: "_QRHMeF4MNo" }, // Las Vegas Aces at Portland Fire
  { date: "2026-07-10", leagueGroup: "wnba", eventId: "401857054", videoId: "sbN0es8-4Cs" }, // Golden State Valkyries at Connecticut Sun
  { date: "2026-07-10", leagueGroup: "wnba", eventId: "401857055", videoId: "GGXVuP_qmf8" }, // Dallas Wings at Toronto Tempo
  { date: "2026-07-10", leagueGroup: "wnba", eventId: "401857056", videoId: "q8R3oBD_2NM" }, // Chicago Sky at Los Angeles Sparks
  { date: "2026-07-11", leagueGroup: "wnba", eventId: "401857057", videoId: "thZxZ18wqnI" }, // New York Liberty at Minnesota Lynx
  { date: "2026-07-11", leagueGroup: "wnba", eventId: "401857058", videoId: "ufAid0Dp33M" }, // Phoenix Mercury at Las Vegas Aces
  { date: "2026-07-11", leagueGroup: "wnba", eventId: "401857059", videoId: "BljnCJv7TAg" }, // Portland Fire at Atlanta Dream

  // NBA Summer League (Las Vegas)
  { date: "2026-07-10", leagueGroup: "nba", eventId: "401879489", videoId: "TMzXSZJlato" }, // Chicago Bulls at Memphis Grizzlies
  { date: "2026-07-10", leagueGroup: "nba", eventId: "401881832", videoId: "vak4ZOGks7U" }, // Boston Celtics at Toronto Raptors
  { date: "2026-07-10", leagueGroup: "nba", eventId: "401881833", videoId: "Fd3qGkD-exI" }, // Oklahoma City Thunder at Los Angeles Lakers
  { date: "2026-07-10", leagueGroup: "nba", eventId: "401881834", videoId: "yZMVnmA9iWk" }, // Portland Trail Blazers at Phoenix Suns
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881835", videoId: "ScKEDHE1Rcg" }, // Miami Heat at Orlando Magic
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881836", videoId: "C3k2NAsQaRE" }, // New Orleans Pelicans at Charlotte Hornets
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881837", videoId: "km9Ml7-hOh4" }, // Indiana Pacers at Philadelphia 76ers
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881838", videoId: "jFiM59ru6J0" }, // New York Knicks at San Antonio Spurs
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881839", videoId: "oapzGvKg1lE" }, // Denver Nuggets at Minnesota Timberwolves
  { date: "2026-07-11", leagueGroup: "nba", eventId: "401881840", videoId: "ngg9qm7CbZ4" }, // Atlanta Hawks at Brooklyn Nets
];

export async function applySeedHighlights(): Promise<void> {
  const byDate = new Map<string, SeedEntry[]>();
  for (const entry of SEED_ENTRIES) {
    const key = `${entry.date}|${entry.leagueGroup}`;
    byDate.set(key, [...(byDate.get(key) ?? []), entry]);
  }

  for (const [key, entries] of byDate) {
    const [date, leagueGroup] = key.split("|") as [string, LeagueGroup];
    try {
      // Ensures a real, fully-formed row exists for each event (via the
      // exact same path every normal request uses - never hand-built),
      // then layers the confirmed video ID on top.
      await getGamesForDate(date, leagueGroup);
      for (const entry of entries) setHighlights(entry.eventId, entry.videoId);
    } catch (err) {
      console.error(`applySeedHighlights: failed for ${key}`, err);
    }
  }
}
