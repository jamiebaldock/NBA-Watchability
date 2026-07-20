// Thin client for ESPN's public (keyless) NHL endpoints - mirrors
// nflEspnClient.ts's/mlbEspnClient.ts's role for their own sports. A separate
// file from espnClient.ts (basketball) since hockey's box score shape
// genuinely differs (periods not quarters, a flat `plays[]` timeline instead
// of a separate `scoringPlays[]` array, forwards/defenses/skaters/goalies
// stat blocks keyed by name, not PTS/REB/AST). Field shapes here were
// confirmed directly against real ESPN responses while building
// backfillRawStatsNhl.ts (see docs/rubric-calibration-procedure.md) - this
// file reuses those same shapes for the live-schedule pipeline rather than
// guessing a second time.
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl";

export interface EspnNhlTeam {
  id: string;
  displayName: string;
  logo?: string;
}

export interface EspnNhlCompetitor {
  id: string;
  homeAway: "home" | "away";
  winner?: boolean;
  team: EspnNhlTeam;
  score: string;
}

export interface EspnNhlStatus {
  period: number;
  displayClock: string;
  type: {
    state: "pre" | "in" | "post";
    completed: boolean;
  };
}

export interface EspnNhlNote {
  type: string;
  // e.g. "West 1st Round - Game 2", "Stanley Cup Final - Game 6" - confirmed
  // directly against real 2026 playoff data, ESPN's only per-round marker for
  // NHL postseason (no week-number field the way NFL's postseason has).
  headline: string;
}

export interface EspnNhlEvent {
  id: string;
  date: string;
  // ESPN's own season classification for NHL - "preseason"/"regular-season"/
  // "post-season" slugs, type 1/2/3 respectively (confirmed directly, same
  // numbering convention as basketball/NFL's).
  season?: { type: number; slug: string };
  competitions: Array<{
    status: EspnNhlStatus;
    competitors: EspnNhlCompetitor[];
    notes?: EspnNhlNote[];
  }>;
}

export interface EspnNhlLinescore {
  displayValue: string;
}

export interface EspnNhlHeaderCompetitor {
  team: { id: string; displayName: string };
  homeAway: "home" | "away";
  score: string;
  winner?: boolean;
  linescores?: EspnNhlLinescore[];
}

export interface EspnNhlPlayParticipant {
  athlete: { displayName: string };
  type: string; // "scorer" | "assister"
}

export interface EspnNhlPlay {
  type: { text: string }; // "Goal" for a scoring play
  text: string;
  awayScore: number;
  homeScore: number;
  period: { number: number; displayValue: string }; // displayValue "SO" marks a shootout attempt
  clock: { displayValue: string }; // "MM:SS" counting down
  strength?: { text: string; abbreviation: string }; // "even-strength" | "power-play" | "short-handed"
  participants?: EspnNhlPlayParticipant[];
  team?: { id: string };
}

export interface EspnNhlTeamStat {
  name: string;
  displayValue: string;
}

export interface EspnNhlTeamBoxscore {
  team: { displayName: string };
  statistics: EspnNhlTeamStat[];
}

export interface EspnNhlPlayerStatBlock {
  name: string; // "forwards" | "defenses" | "skaters" | "goalies"
  keys: string[];
  athletes: Array<{ athlete: { displayName: string }; stats: string[] }>;
}

export interface EspnNhlPlayerBoxscore {
  team: { displayName: string };
  statistics: EspnNhlPlayerStatBlock[];
}

export interface EspnNhlSummary {
  header: {
    competitions: Array<{
      status: { type: { completed: boolean } };
      competitors: EspnNhlHeaderCompetitor[];
    }>;
  };
  // Regulation is 3 periods for NHL (not basketball's/NFL's 4) - read off
  // this field rather than hardcoded, confirmed directly against a real
  // response.
  format?: { regulation?: { periods: number } };
  plays?: EspnNhlPlay[];
  boxscore?: {
    teams: EspnNhlTeamBoxscore[];
    players: EspnNhlPlayerBoxscore[];
  };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`ESPN NHL request failed: ${res.status} ${res.statusText} (${url})`);
  }
  return (await res.json()) as T;
}

/** dateYyyymmdd must be in YYYYMMDD form, matching espnClient.ts's fetchScoreboard. */
export async function fetchNhlScoreboard(dateYyyymmdd: string): Promise<EspnNhlEvent[]> {
  const data = await getJson<{ events?: EspnNhlEvent[] }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchNhlSummary(eventId: string): Promise<EspnNhlSummary> {
  return getJson<EspnNhlSummary>(`${BASE_PATH}/summary?event=${eventId}`);
}

// NHL's calendar IS the flat per-date-string array basketball's
// fetchCalendarDates returns (confirmed directly - a single call returned
// 226 real dates spanning preseason through the Stanley Cup Final), unlike
// MLB's sparse calendar or NFL's grouped-by-stage shape - no day-by-day scan
// or group-parsing workaround needed for NHL's season window.
export async function fetchNhlCalendarDates(dateYyyymmdd: string): Promise<string[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: string[] }> }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  return (data.leagues?.[0]?.calendar ?? []).map((d) => d.slice(0, 10));
}

interface EspnNhlTeamScheduleLogo {
  href: string;
  rel: string[];
}

// The /teams/{id}/schedule endpoint's shape genuinely differs from the
// scoreboard's EspnNhlEvent in the same three ways MLB's/NFL's own
// team-schedule endpoints do (all confirmed directly against a real response
// - Anaheim Ducks, team id 25 - before writing this, not assumed from
// precedent alone):
//   - competitor.team only ever carries a logos[] array, not a single logo
//     string.
//   - the season-type marker lives at event.seasonType.type, not
//     event.season.type/slug like the scoreboard.
//   - notes (the postseason round headline) live at the same competitions[0]
//     level as the scoreboard's shape, confirmed present here too.
interface EspnNhlTeamScheduleEvent {
  id: string;
  date: string;
  seasonType?: { type: number };
  competitions: Array<{
    status: EspnNhlStatus;
    competitors: Array<{
      id: string;
      homeAway: "home" | "away";
      winner?: boolean;
      score?: { value: number; displayValue: string };
      team: {
        id: string;
        displayName: string;
        logos?: EspnNhlTeamScheduleLogo[];
      };
    }>;
    notes?: EspnNhlNote[];
  }>;
}

function logoFromLogos(logos: EspnNhlTeamScheduleLogo[] | undefined, rel: string): string | undefined {
  return logos?.find((l) => l.rel.includes(rel))?.href;
}

function toStandardNhlEvent(raw: EspnNhlTeamScheduleEvent): EspnNhlEvent {
  return {
    id: raw.id,
    date: raw.date,
    season: raw.seasonType ? { type: raw.seasonType.type, slug: "" } : undefined,
    competitions: raw.competitions.map((c) => ({
      status: c.status,
      competitors: c.competitors.map((comp) => ({
        id: comp.id,
        homeAway: comp.homeAway,
        winner: comp.winner,
        score: comp.score?.displayValue ?? "0",
        team: {
          id: comp.team.id,
          displayName: comp.team.displayName,
          logo: logoFromLogos(comp.team.logos, "scoreboard") ?? logoFromLogos(comp.team.logos, "default"),
        },
      })),
      notes: c.notes,
    })),
  };
}

/**
 * A team's own real schedule (past and upcoming games, current season) in
 * one call - the Favorites tab's Games page uses this instead of scanning
 * day-by-day, same reasoning as mlbEspnClient.ts's/nflEspnClient.ts's own
 * fetchTeamSchedule equivalents.
 */
export async function fetchNhlTeamSchedule(teamId: string): Promise<EspnNhlEvent[]> {
  const data = await getJson<{ events?: EspnNhlTeamScheduleEvent[] }>(`${BASE_PATH}/teams/${teamId}/schedule`);
  return (data.events ?? []).map(toStandardNhlEvent);
}
