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
  "wnba",
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
  // ESPN's own season classification - type 1/2/3 map to slug
  // preseason/regular-season/post-season respectively. Read fresh off each
  // scoreboard fetch rather than cached, since it's cheap and always current.
  season?: { type: number; slug: string };
  competitions: Array<{
    status: EspnStatus;
    competitors: EspnCompetitor[];
    // Only present for specially-designated games - e.g. NBA Cup (In-Season
    // Tournament) group play/quarterfinals/semifinals/championship carry a
    // headline like "NBA Cup - Group Play" here. Absent for ordinary games.
    notes?: Array<{ type: string; headline: string }>;
    venue?: {
      fullName?: string;
      address?: { city?: string; state?: string };
    };
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

// Standings, stats leaders, and news are only meaningful for the two real
// leagues, never the Summer League tournament variants.
export type ContentLeague = "nba" | "wnba";

export interface EspnStandingsEntry {
  team: {
    id: string;
    shortDisplayName: string;
    abbreviation: string;
    logos?: Array<{ href: string }>;
  };
  // value is the full-precision number (e.g. winPercent 0.5121951); displayValue
  // is a rounded display string (".512") - not precise enough to break ties.
  stats: Array<{ name: string; value?: number; displayValue: string }>;
}

export interface EspnStandingsGroup {
  name: string;
  isConference?: boolean;
  standings?: {
    seasonDisplayName: string;
    entries: EspnStandingsEntry[];
  };
  children?: EspnStandingsGroup[];
}

interface EspnStandingsRoot {
  children: EspnStandingsGroup[];
}

/** Season is ESPN's "ends in" convention (2025-26 season = 2026). Returns null if that season has no standings data yet (not started). */
export async function fetchStandings(league: ContentLeague, seasonYear: number): Promise<EspnStandingsRoot | null> {
  const data = await getJson<EspnStandingsRoot>(
    `https://site.api.espn.com/apis/v2/sports/basketball/${league}/standings?season=${seasonYear}`
  );
  const hasEntries = data.children?.some((group) => (group.standings?.entries?.length ?? 0) > 0);
  return hasEntries ? data : null;
}

export interface EspnLeaderEntry {
  displayValue: string;
  athlete: { $ref: string };
  team: { $ref: string };
}

export interface EspnLeaderCategory {
  name: string;
  displayName: string;
  abbreviation: string;
  leaders: EspnLeaderEntry[];
}

interface EspnLeadersRoot {
  categories: EspnLeaderCategory[];
}

/** Same season convention/fallback contract as fetchStandings. */
export async function fetchLeaders(league: ContentLeague, seasonYear: number): Promise<EspnLeadersRoot | null> {
  try {
    return await getJson<EspnLeadersRoot>(
      `https://sports.core.api.espn.com/v2/sports/basketball/leagues/${league}/seasons/${seasonYear}/types/2/leaders`
    );
  } catch {
    return null;
  }
}

/** Resolves a leader's athlete $ref to a display name (small ~7KB fetch each, cached daily by the caller). */
export async function fetchAthleteName(ref: string): Promise<string> {
  const data = await getJson<{ displayName: string }>(ref);
  return data.displayName;
}

/** Resolves a leader's team $ref to its abbreviation/logo (small ~9KB fetch each, cached daily by the caller). */
export async function fetchTeamInfo(ref: string): Promise<{ abbreviation: string; logo?: string }> {
  const data = await getJson<{ abbreviation: string; logos?: Array<{ href: string }> }>(ref);
  return { abbreviation: data.abbreviation, logo: data.logos?.[0]?.href };
}

export interface EspnNewsArticle {
  id: number;
  headline: string;
  description?: string;
  published: string;
  images?: Array<{ url: string }>;
  links?: { web?: { href?: string } };
}

export async function fetchNews(league: ContentLeague, limit: number): Promise<EspnNewsArticle[]> {
  const data = await getJson<{ articles?: EspnNewsArticle[] }>(
    `${basePath(league)}/news?limit=${limit}`
  );
  return data.articles ?? [];
}
