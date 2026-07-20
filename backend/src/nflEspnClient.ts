// Thin client for ESPN's public (keyless) NFL endpoints - mirrors
// mlbEspnClient.ts's role for baseball. A separate file from espnClient.ts
// (basketball) since NFL's box score shape genuinely differs (quarters,
// scoringPlays timeline, passing/rushing/receiving/defensive stat blocks
// keyed by name, not PTS/REB/AST), even though the top-level event/
// competitor/status shape is otherwise identical to basketball's. Field
// shapes here were confirmed directly against real ESPN responses while
// building backfillRawStatsNfl.ts (see docs/rubric-calibration-procedure.md)
// - this file reuses those same shapes for the live-schedule pipeline
// rather than guessing a second time.
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/football/nfl";

export interface EspnNflTeam {
  id: string;
  displayName: string;
  logo?: string;
}

export interface EspnNflCompetitor {
  id: string;
  homeAway: "home" | "away";
  winner?: boolean;
  team: EspnNflTeam;
  score: string;
}

export interface EspnNflStatus {
  period: number;
  displayClock: string;
  type: {
    state: "pre" | "in" | "post";
    completed: boolean;
  };
}

export interface EspnNflEvent {
  id: string;
  date: string;
  // ESPN's own season classification - 1=preseason, 2=regular-season,
  // 3=postseason. Only regular/postseason events are ever shown (see
  // nflGamesService.ts), same "skip preseason" rule
  // backfillRawStatsNfl.ts already validated against real data.
  season?: { type: number; slug: string };
  competitions: Array<{
    status: EspnNflStatus;
    competitors: EspnNflCompetitor[];
  }>;
}

export interface EspnNflLinescore {
  displayValue: string;
}

export interface EspnNflHeaderCompetitor {
  team: { id: string; displayName: string };
  homeAway: "home" | "away";
  score: string;
  winner?: boolean;
  linescores?: EspnNflLinescore[];
}

export interface EspnNflScoringPlay {
  awayScore: number;
  homeScore: number;
  period: { number: number };
  clock: { value: number };
}

export interface EspnNflTeamStat {
  name: string;
  displayValue: string;
}

export interface EspnNflTeamBoxscore {
  team: { displayName: string };
  statistics: EspnNflTeamStat[];
}

export interface EspnNflPlayerStatBlock {
  name: string;
  keys: string[];
  athletes: Array<{ athlete: { displayName: string }; stats: string[] }>;
}

export interface EspnNflPlayerBoxscore {
  team: { displayName: string };
  statistics: EspnNflPlayerStatBlock[];
}

export interface EspnNflSummary {
  header: {
    competitions: Array<{
      status: { type: { completed: boolean } };
      competitors: EspnNflHeaderCompetitor[];
    }>;
  };
  scoringPlays?: EspnNflScoringPlay[];
  boxscore?: {
    teams: EspnNflTeamBoxscore[];
    players: EspnNflPlayerBoxscore[];
  };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`ESPN NFL request failed: ${res.status} ${res.statusText} (${url})`);
  }
  return (await res.json()) as T;
}

/** dateYyyymmdd must be in YYYYMMDD form, matching espnClient.ts's fetchScoreboard. */
export async function fetchNflScoreboard(dateYyyymmdd: string): Promise<EspnNflEvent[]> {
  const data = await getJson<{ events?: EspnNflEvent[] }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchNflSummary(eventId: string): Promise<EspnNflSummary> {
  return getJson<EspnNflSummary>(`${BASE_PATH}/summary?event=${eventId}`);
}

// NFL's calendar shape is NOT the flat per-date-string array basketball's
// fetchCalendarDates/MLB's fetchMlbCalendarDates return - confirmed directly
// against a real response, not assumed from either precedent. It's grouped
// by season stage (Preseason/Regular Season/Postseason/Off Season, keyed by
// [value]: "1"/"2"/"3"/"4"), each with its own real startDate/endDate and a
// week-level entries[] breakdown (each entry itself a real week with its own
// startDate/endDate, e.g. "Week 1", "Sep 4-9"). This actually makes finding
// the real regular-season start *simpler* than MLB's own day-by-day scan
// workaround needed (see seasonWindowService.ts's getNflSeasonWindow) - the
// Regular Season group's startDate is already the real answer, no scanning
// required.
export interface EspnNflCalendarEntry {
  label: string;
  startDate: string;
  endDate: string;
}

export interface EspnNflCalendarGroup {
  label: string;
  value: string;
  startDate: string;
  endDate: string;
  entries: EspnNflCalendarEntry[];
}

export async function fetchNflCalendarGroups(dateYyyymmdd: string): Promise<EspnNflCalendarGroup[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: EspnNflCalendarGroup[] }> }>(`${BASE_PATH}/scoreboard?dates=${dateYyyymmdd}`);
  return data.leagues?.[0]?.calendar ?? [];
}

interface EspnNflTeamScheduleLogo {
  href: string;
  rel: string[];
}

// The /teams/{id}/schedule endpoint's shape genuinely differs from the
// scoreboard's EspnNflEvent in the same three ways MLB's own team-schedule
// endpoint does (all re-confirmed directly against a real response -
// Indianapolis Colts, team id 11 - before writing this, not assumed from
// MLB's precedent alone):
//   - competitor.team only ever carries a logos[] array, not a single logo
//     string.
//   - competitor.score is entirely absent for a not-yet-played game (no
//     placeholder at all, unlike the scoreboard's "0"), and a
//     { value, displayValue } object for a played one.
//   - the season-type marker lives at event.seasonType.type, not
//     event.season.type/slug like the scoreboard.
interface EspnNflTeamScheduleEvent {
  id: string;
  date: string;
  seasonType?: { type: number };
  competitions: Array<{
    status: EspnNflStatus;
    competitors: Array<{
      id: string;
      homeAway: "home" | "away";
      winner?: boolean;
      score?: { value: number; displayValue: string };
      team: {
        id: string;
        displayName: string;
        logos?: EspnNflTeamScheduleLogo[];
      };
    }>;
  }>;
}

function logoFromLogos(logos: EspnNflTeamScheduleLogo[] | undefined, rel: string): string | undefined {
  return logos?.find((l) => l.rel.includes(rel))?.href;
}

function toStandardNflEvent(raw: EspnNflTeamScheduleEvent): EspnNflEvent {
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
    })),
  };
}

/**
 * A team's own real schedule (past and upcoming games, current season) in
 * one call - the Favorites tab's Games page uses this instead of scanning
 * day-by-day, same reasoning as mlbEspnClient.ts's own fetchMlbTeamSchedule.
 * No client-side date windowing - whatever ESPN's own endpoint currently
 * returns for this team is shown as-is.
 */
export async function fetchNflTeamSchedule(teamId: string): Promise<EspnNflEvent[]> {
  const data = await getJson<{ events?: EspnNflTeamScheduleEvent[] }>(`${BASE_PATH}/teams/${teamId}/schedule`);
  return (data.events ?? []).map(toStandardNflEvent);
}
