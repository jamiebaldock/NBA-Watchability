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

interface EspnMlbTeamScheduleLogo {
  href: string;
  rel: string[];
}

// The /teams/{id}/schedule endpoint's shape genuinely differs from the
// scoreboard's EspnMlbEvent in three ways, all confirmed directly against a
// real response (Arizona Diamondbacks, team id 29) before writing this:
//   - competitor.team only ever carries a logos[] array, same
//     scoreboard-vs-team-schedule quirk espnClient.ts's own
//     EspnTeamScheduleEvent/toStandardEvent already documents for
//     basketball.
//   - competitor.score is a { value, displayValue } object here, not the
//     plain string every other MLB endpoint in this file returns (absent
//     entirely for a not-yet-played game, unlike the scoreboard which
//     always has a "0" placeholder).
//   - the season-type marker lives at event.seasonType.type, not
//     event.season.type/slug like the scoreboard - and this endpoint never
//     actually returns a spring-training (type 1) event at all (verified:
//     every event in a full season's worth of results was type 2/regular,
//     even ones from March), so the slug-based half of
//     mlbGamesService.ts's isRealSeasonEvent check never has real data to
//     act on here; type alone is preserved for it regardless, for
//     consistency and defensiveness against a postseason (type 3) event.
// toStandardMlbEvent below normalizes all three back into EspnMlbEvent's
// shape so none of this leaks into processMlbEvent, which already expects
// the scoreboard's shape.
interface EspnMlbTeamScheduleEvent {
  id: string;
  date: string;
  seasonType?: { type: number };
  competitions: Array<{
    status: EspnMlbStatus;
    competitors: Array<{
      id: string;
      homeAway: "home" | "away";
      winner?: boolean;
      score?: { value: number; displayValue: string };
      team: {
        id: string;
        displayName: string;
        logos?: EspnMlbTeamScheduleLogo[];
      };
    }>;
  }>;
}

function logoFromLogos(logos: EspnMlbTeamScheduleLogo[] | undefined, rel: string): string | undefined {
  return logos?.find((l) => l.rel.includes(rel))?.href;
}

function toStandardMlbEvent(raw: EspnMlbTeamScheduleEvent): EspnMlbEvent {
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
 * day-by-day, same reasoning as espnClient.ts's own fetchTeamSchedule for
 * basketball. No client-side date windowing - whatever ESPN's own endpoint
 * currently returns for this team is shown as-is.
 */
export async function fetchMlbTeamSchedule(teamId: string): Promise<EspnMlbEvent[]> {
  const data = await getJson<{ events?: EspnMlbTeamScheduleEvent[] }>(`${BASE_PATH}/teams/${teamId}/schedule`);
  return (data.events ?? []).map(toStandardMlbEvent);
}
