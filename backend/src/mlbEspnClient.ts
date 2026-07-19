// Thin client for ESPN's public (keyless) MLB endpoints - mirrors
// soccerEspnClient.ts's role for baseball. A separate file from
// espnClient.ts (basketball) since MLB's box score shape genuinely differs
// (linescores/innings, batting/pitching/fielding stat blocks keyed by name,
// not PTS/REB/AST), even though the top-level event/competitor/status shape
// is otherwise identical to basketball's. Field shapes here (header,
// boxscore.teams, boxscore.players' keys/athletes battingBlock) were
// confirmed directly against real ESPN responses while building
// backfillRawStatsMlb.ts (see docs/rubric-calibration-procedure.md) - this
// file reuses those same shapes for the live-schedule pipeline rather than
// guessing a second time.
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb";

export interface EspnMlbTeam {
  id: string;
  displayName: string;
  logo?: string;
}

export interface EspnMlbCompetitor {
  id: string;
  homeAway: "home" | "away";
  winner?: boolean;
  team: EspnMlbTeam;
  score: string;
}

export interface EspnMlbStatus {
  period: number;
  displayClock: string;
  type: {
    state: "pre" | "in" | "post";
    completed: boolean;
  };
}

export interface EspnMlbEvent {
  id: string;
  date: string;
  // ESPN's own season classification - 1=preseason (spring training),
  // 2=regular-season, 3=postseason. Only regular/postseason events are ever
  // shown (see mlbGamesService.ts), same "skip spring training" rule
  // backfillRawStatsMlb.ts already validated against real data.
  season?: { type: number; slug: string };
  competitions: Array<{
    status: EspnMlbStatus;
    competitors: EspnMlbCompetitor[];
  }>;
}

export interface EspnMlbLinescore {
  displayValue: string;
}

export interface EspnMlbHeaderCompetitor {
  team: { id: string };
  homeAway: "home" | "away";
  winner?: boolean;
  linescores?: EspnMlbLinescore[];
}

export interface EspnMlbStatValue {
  name: string;
  value?: number;
  displayValue: string;
}

export interface EspnMlbTeamBoxscore {
  team: { id: string; displayName: string };
  homeAway: "home" | "away";
  statistics: Array<{ name: string; stats: EspnMlbStatValue[] }>;
}

export interface EspnMlbPlayerBattingBlock {
  keys: string[];
  athletes: Array<{ athlete: { displayName: string }; stats: string[] }>;
}

export interface EspnMlbPlayerBoxscore {
  team: { id: string };
  statistics: EspnMlbPlayerBattingBlock[];
}

export interface EspnMlbSummary {
  header: {
    competitions: Array<{
      status: { type: { completed: boolean } };
      competitors: EspnMlbHeaderCompetitor[];
    }>;
  };
  boxscore?: {
    teams: EspnMlbTeamBoxscore[];
    players: EspnMlbPlayerBoxscore[];
  };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`ESPN MLB request failed: ${res.status} ${res.statusText} (${url})`);
  }
  return (await res.json()) as T;
}

/** dateYyyymmdd must be in YYYYMMDD form, matching espnClient.ts's fetchScoreboard. */
export async function fetchMlbScoreboard(dateYyyymmdd: string): Promise<EspnMlbEvent[]> {
  const data = await getJson<{ events?: EspnMlbEvent[] }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchMlbSummary(eventId: string): Promise<EspnMlbSummary> {
  return getJson<EspnMlbSummary>(`${BASE_PATH}/summary?event=${eventId}`);
}

/** Mirrors espnClient.ts's fetchCalendarDates - a flat list of real per-date strings for MLB's scoreboard, used to find the next scheduled date. */
export async function fetchMlbCalendarDates(dateYyyymmdd: string): Promise<string[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: string[] }> }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  const calendar = data.leagues?.[0]?.calendar ?? [];
  return calendar.map((d) => d.slice(0, 10));
}
