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
export const SOCCER_LEAGUES = ["eng.1", "esp.1", "fifa.world"] as const;
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
    // e.g. "STATUS_FULL_TIME" (regulation), "STATUS_FINAL_AET" (decided in
    // extra time), "STATUS_FINAL_PEN" (decided by penalty shootout) -
    // confirmed directly against real World Cup matches (2014 Germany-
    // Algeria AET, 2022 Argentina-France PEN final). Used to derive
    // wentToExtraTime/decidedByShootout in soccerGameMapper.ts.
    name?: string;
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
  id: string;
  displayName: string;
  logos?: Array<{ href: string; rel: string[] }>;
}

/** The full real club list for [league] (20 for eng.1/esp.1) - backs the favorite-teams search/browse screen, same reasoning as espnClient.ts's fetchTeams. */
export async function fetchSoccerTeams(league: SoccerLeague): Promise<Array<{ id: string; displayName: string; logo?: string }>> {
  const data = await getJson<{ sports: Array<{ leagues: Array<{ teams: Array<{ team: EspnSoccerTeamListEntry }> }> }> }>(
    `${basePath(league)}/teams?limit=100`
  );
  const teams = data.sports?.[0]?.leagues?.[0]?.teams ?? [];
  return teams.map((t) => ({
    id: t.team.id,
    displayName: t.team.displayName,
    logo: t.team.logos?.find((l) => l.rel.includes("default"))?.href ?? t.team.logos?.[0]?.href
  }));
}

export interface EspnSoccerRosterAthlete {
  id: string;
  displayName: string;
}

/** A club's current player list - soccer analogue of espnClient.ts's fetchRoster. */
export async function fetchSoccerRoster(teamId: string, league: SoccerLeague): Promise<EspnSoccerRosterAthlete[]> {
  const data = await getJson<{ athletes: EspnSoccerRosterAthlete[] }>(`${basePath(league)}/teams/${teamId}/roster`);
  return data.athletes ?? [];
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

interface EspnTournamentStageEntry {
  startDate: string;
  endDate: string;
}

interface EspnTournamentCalendarEntry {
  startDate: string;
  endDate: string;
  entries?: EspnTournamentStageEntry[];
}

interface EspnSoccerScheduleLogo {
  href: string;
  rel: string[];
}

// Same shape difference as espnClient.ts's fetchTeamSchedule - the
// /teams/{id}/schedule endpoint's competitor.team only carries a logos[]
// array, not the plain team.logo string every other soccer endpoint in
// this file returns. Normalized back into EspnSoccerEvent's shape below so
// the fetch-layer difference doesn't leak into shared game-processing logic.
interface EspnSoccerScheduleEvent {
  id: string;
  date: string;
  competitions: Array<{
    status: EspnSoccerStatus;
    competitors: Array<{
      id: string;
      homeAway: "home" | "away";
      score?: string;
      team: {
        id: string;
        displayName: string;
        logos?: EspnSoccerScheduleLogo[];
      };
    }>;
    notes?: Array<{ type: string; headline: string }>;
  }>;
}

function logoFromLogos(logos: EspnSoccerScheduleLogo[] | undefined, rel: string): string | undefined {
  return logos?.find((l) => l.rel.includes(rel))?.href;
}

function toStandardSoccerEvent(raw: EspnSoccerScheduleEvent): EspnSoccerEvent {
  return {
    id: raw.id,
    date: raw.date,
    competitions: raw.competitions.map((c) => ({
      status: c.status,
      competitors: c.competitors.map((comp) => ({
        id: comp.id,
        homeAway: comp.homeAway,
        score: comp.score ?? "0",
        team: {
          id: comp.team.id,
          displayName: comp.team.displayName,
          logo: logoFromLogos(comp.team.logos, "default") ?? logoFromLogos(comp.team.logos, "scoreboard"),
        },
      })),
      notes: c.notes,
    })),
  };
}

/**
 * A team's own real schedule (past and upcoming, current season) in one
 * call - soccer analogue of espnClient.ts's fetchTeamSchedule, same
 * reasoning (the Favorites tab's Games page). No client-side date
 * windowing - whatever ESPN currently returns is shown as-is.
 */
export async function fetchSoccerTeamSchedule(teamId: string, league: SoccerLeague): Promise<EspnSoccerEvent[]> {
  const data = await getJson<{ events?: EspnSoccerScheduleEvent[] }>(`${basePath(league)}/teams/${teamId}/schedule`);
  return (data.events ?? []).map(toStandardSoccerEvent);
}

/**
 * Mirrors espnClient.ts's fetchCalendarDates - backs the season-window/
 * calendar-picker derivation for soccer groups. A domestic league's
 * `calendar` is a flat list of real per-matchday date strings, but a
 * tournament's (fifa.world) is shaped completely differently - one
 * stage-summary object per named stage (Group/Round of 32/.../Final,
 * each with its own startDate/endDate), not a per-game list - confirmed
 * directly against ESPN's real response for each, not assumed. Calling
 * `.slice` on a tournament's calendar entries as if they were date strings
 * throws at runtime, which would 500 the /season-window endpoint the
 * mobile app calls unconditionally on every Games-tab load - branch on the
 * actual shape rather than assuming every soccer leagueGroup looks like
 * eng.1/esp.1.
 */
export async function fetchSoccerCalendarDates(dateYyyymmdd: string, league: SoccerLeague): Promise<string[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: Array<string | EspnTournamentCalendarEntry> }> }>(
    `${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`
  );
  const calendar = data.leagues?.[0]?.calendar ?? [];
  if (calendar.length === 0) return [];

  if (typeof calendar[0] === "string") {
    return (calendar as string[]).map((d) => d.slice(0, 10));
  }

  // Tournament shape: union every named stage's own [startDate, endDate]
  // range into individual days - coarser than a real matchday list (a rest
  // day within a stage's range gets included too, with no actual fixture),
  // but correct and simple, and cheap for a several-week tournament window.
  const dates = new Set<string>();
  for (const stage of calendar as EspnTournamentCalendarEntry[]) {
    for (const entry of stage.entries ?? []) {
      let day = new Date(entry.startDate.slice(0, 10));
      const end = new Date(entry.endDate.slice(0, 10));
      while (day <= end) {
        dates.add(day.toISOString().slice(0, 10));
        day = new Date(day.getTime() + 24 * 60 * 60 * 1000);
      }
    }
  }
  return [...dates].sort();
}
