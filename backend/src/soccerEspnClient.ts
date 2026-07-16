// Thin client for ESPN's public (keyless) soccer endpoints - a separate file
// from espnClient.ts (not an extension of it) because soccer's ESPN API
// shape genuinely differs from basketball's: events carry a full descriptive
// season.slug ("2025-26-english-premier-league", not a short stage keyword
// like basketball's "regular-season"), goals arrive as keyEvents entries
// rather than play-by-play, and team box-score stats are keyed differently
// (shotsOnTarget/saves/redCards, not PTS/REB/AST). status.type.state ("pre"/
// "in"/"post") matches basketball's exactly, though, so gameMapper.ts's
// mapEspnState is reused as-is rather than duplicated - see
// soccerGameMapper.ts.
export const SOCCER_LEAGUES = ["eng.1", "esp.1"] as const;
export type SoccerLeague = (typeof SOCCER_LEAGUES)[number];

function basePath(league: SoccerLeague): string {
  return `https://site.api.espn.com/apis/site/v2/sports/soccer/${league}`;
}

export interface EspnSoccerTeam {
  id: string;
  displayName: string;
  logo?: string;
}

export interface EspnSoccerCompetitor {
  id: string;
  homeAway: "home" | "away";
  winner?: boolean;
  team: EspnSoccerTeam;
  score: string;
}

export interface EspnSoccerStatus {
  period: number;
  displayClock: string;
  type: {
    state: "pre" | "in" | "post";
    completed: boolean;
  };
}

export interface EspnSoccerEvent {
  id: string;
  date: string;
  season?: { year: number; slug: string };
  competitions: Array<{
    status: EspnSoccerStatus;
    competitors: EspnSoccerCompetitor[];
    notes?: Array<{ type: string; headline: string }>;
  }>;
}

export interface EspnSoccerKeyEvent {
  type: { id: string; text: string; type: string };
  text?: string;
  scoringPlay: boolean;
  clock: { value: number; displayValue: string };
  period: { number: number };
  team?: { id: string; displayName: string };
  participants?: Array<{ athlete?: { id: string; displayName: string } }>;
}

export interface EspnSoccerBoxscoreTeam {
  team: { id: string; displayName: string };
  statistics: Array<{ name: string; displayValue: string; label: string }>;
  homeAway?: string;
}

export interface EspnSoccerSummary {
  boxscore?: { teams: EspnSoccerBoxscoreTeam[] };
  keyEvents?: EspnSoccerKeyEvent[];
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`ESPN soccer request failed: ${res.status} ${res.statusText} (${url})`);
  }
  return (await res.json()) as T;
}

/** dateYyyymmdd must be in YYYYMMDD form (ESPN's scoreboard date format), matching espnClient.ts's fetchScoreboard. */
export async function fetchSoccerScoreboard(dateYyyymmdd: string, league: SoccerLeague): Promise<EspnSoccerEvent[]> {
  const data = await getJson<{ events?: EspnSoccerEvent[] }>(`${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchSoccerSummary(eventId: string, league: SoccerLeague): Promise<EspnSoccerSummary> {
  return getJson<EspnSoccerSummary>(`${basePath(league)}/summary?event=${eventId}`);
}

// The teams-list endpoint's own team shape carries a `logos` array (each
// entry tagged "default"/"dark"/etc.) rather than the single `logo` string
// field EspnSoccerTeam models (that shape matches the scoreboard/summary
// endpoints used everywhere else in this file, not this one) - kept as its
// own local type rather than widening EspnSoccerTeam for every other caller.
interface EspnSoccerTeamListEntry {
  displayName: string;
  logos?: Array<{ href: string; rel: string[] }>;
}

/** The full real club list for [league] (20 for eng.1/esp.1) - backs the favorite-teams search/browse screen, same reasoning as espnClient.ts's fetchTeams. */
export async function fetchSoccerTeams(league: SoccerLeague): Promise<Array<{ displayName: string; logo?: string }>> {
  const data = await getJson<{ sports: Array<{ leagues: Array<{ teams: Array<{ team: EspnSoccerTeamListEntry }> }> }> }>(
    `${basePath(league)}/teams?limit=100`
  );
  const teams = data.sports?.[0]?.leagues?.[0]?.teams ?? [];
  return teams.map((t) => ({
    displayName: t.team.displayName,
    logo: t.team.logos?.find((l) => l.rel.includes("default"))?.href ?? t.team.logos?.[0]?.href
  }));
}

/**
 * Wide date-range scoreboard query - backfill-only (the live schedule path
 * only ever wants a single day, via fetchSoccerScoreboard above). Confirmed
 * directly against ESPN: unlike a single-day query, a multi-month range
 * needs an explicit &limit or it silently truncates to 100 events well
 * short of a full ~380-match season; limit=500 comfortably covers one
 * season for either eng.1 or esp.1 in a single request.
 */
export async function fetchSoccerScoreboardRange(
  startYyyymmdd: string,
  endYyyymmdd: string,
  league: SoccerLeague,
  limit = 500
): Promise<EspnSoccerEvent[]> {
  const data = await getJson<{ events?: EspnSoccerEvent[] }>(
    `${basePath(league)}/scoreboard?dates=${startYyyymmdd}-${endYyyymmdd}&limit=${limit}`
  );
  return data.events ?? [];
}

/** Mirrors espnClient.ts's fetchCalendarDates - backs the season-window/calendar-picker derivation for soccer groups. */
export async function fetchSoccerCalendarDates(dateYyyymmdd: string, league: SoccerLeague): Promise<string[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: string[] }> }>(
    `${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`
  );
  return (data.leagues?.[0]?.calendar ?? []).map((d) => d.slice(0, 10));
}
