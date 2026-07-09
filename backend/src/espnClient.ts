// Thin client for ESPN's public (keyless) NBA endpoints. Unofficial but widely
// used for exactly this kind of hobby project; named as an example source in
// nba-watchability-spec.md section 2 point 12.
//
// Regular season/playoffs and each NBA Summer League tournament are separate
// "sports" as far as ESPN's site API is concerned, each with their own
// scoreboard AND summary path — an event fetched from one league's scoreboard
// 404s if you ask the wrong league's summary endpoint for it, so callers must
// always pair an event with the League it came from.
export const ALL_LEAGUES = [
  "nba",
  "nba-summer-las-vegas",
  "nba-summer-utah",
  "nba-summer-sacramento",
] as const;

export type League = (typeof ALL_LEAGUES)[number];

function basePath(league: League): string {
  return `https://site.api.espn.com/apis/site/v2/sports/basketball/${league}`;
}

export interface EspnTeam {
  id: string;
  name: string;
  displayName: string;
  logo?: string;
}

export interface EspnCompetitor {
  id: string;
  homeAway: "home" | "away";
  winner?: boolean;
  team: EspnTeam;
  score: string;
  records?: Array<{ name: string; type: string; summary: string }>;
}

export interface EspnStatus {
  period: number;
  displayClock: string;
  type: {
    state: "pre" | "in" | "post";
    completed: boolean;
  };
}

export interface EspnEvent {
  id: string;
  date: string;
  competitions: Array<{
    status: EspnStatus;
    competitors: EspnCompetitor[];
  }>;
}

export interface EspnPlay {
  sequenceNumber: string;
  period: { number: number };
  clock: { displayValue: string };
  awayScore: number;
  homeScore: number;
  scoringPlay: boolean;
  shootingPlay: boolean;
  scoreValue: number;
  team?: { id: string };
  type: { text: string };
}

export interface EspnBoxscoreAthlete {
  athlete: { displayName: string };
  stats: string[];
}

export interface EspnBoxscoreTeamPlayers {
  team: EspnTeam;
  statistics: Array<{
    labels: string[];
    athletes: EspnBoxscoreAthlete[];
  }>;
}

export interface EspnSummary {
  plays?: EspnPlay[];
  boxscore?: {
    players?: EspnBoxscoreTeamPlayers[];
  };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`ESPN request failed: ${res.status} ${res.statusText} (${url})`);
  }
  return (await res.json()) as T;
}

/** dateYyyymmdd must be in YYYYMMDD form (ESPN's scoreboard date format). */
export async function fetchScoreboard(dateYyyymmdd: string, league: League): Promise<EspnEvent[]> {
  const data = await getJson<{ events: EspnEvent[] }>(`${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchSummary(eventId: string, league: League): Promise<EspnSummary> {
  return getJson<EspnSummary>(`${basePath(league)}/summary?event=${eventId}`);
}

export function toEspnDate(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}${m}${d}`;
}
