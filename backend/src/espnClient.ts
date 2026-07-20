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
  // Only present on the scoreboard's competitor.team shape (confirmed
  // directly - a real singular URL there). The /teams list endpoint
  // (fetchTeams/fetchTeamsForSport below) has NO such field on any sport,
  // basketball included - it only ever returns the logos[] array below, the
  // same "logos[] not logo" shape the team-schedule endpoints already have
  // their own documented quirk for. teamsService.ts's getSportTeams reads
  // logos instead of this field for exactly that reason.
  logo?: string;
  logos?: Array<{ href: string; rel: string[] }>;
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

interface EspnTeamScheduleLogo {
  href: string;
  rel: string[];
}

// The /teams/{id}/schedule endpoint's own competitor.team shape only ever
// carries a logos[] array (many variants: default/dark/scoreboard/...), not
// the plain team.logo string field every other endpoint in this file
// returns - confirmed directly against a real response, not assumed from
// the scoreboard shape. toStandardEvent below normalizes this back into
// the same EspnEvent shape every other pipeline function already expects,
// so the fetch-layer shape difference doesn't leak into game-processing
// logic shared with the day-based schedule fetch.
interface EspnTeamScheduleEvent {
  id: string;
  date: string;
  // The season-type marker here lives at event.seasonType.type (confirmed
  // directly - {id, type, name, abbreviation}, e.g. type:1/"Preseason",
  // type:2/"Regular Season"), NOT event.season.slug like the scoreboard's
  // own shape (that event.season field exists here too, but only ever
  // carries {year, displayName} - no slug at all). Without mapping this
  // back into the slug string gameMapper.ts's deriveSeasonStage actually
  // switches on, every game sourced from this endpoint silently got
  // season=undefined forever, so the Favorites tab's competition label (and
  // therefore its league-logo tile, which only renders once a real label
  // exists - see GameCard.kt's CompetitionLabelWithLogo) never showed for
  // ANY favorited team's upcoming/live game, NBA and WNBA alike - not a
  // WNBA-specific bug, just more visible there since WNBA teams are
  // favorited more often to check the Games page for.
  seasonType?: { type: number };
  competitions: Array<{
    status: EspnStatus;
    competitors: Array<{
      id: string;
      homeAway: "home" | "away";
      score?: string;
      team: {
        id: string;
        displayName: string;
        logos?: EspnTeamScheduleLogo[];
      };
    }>;
    notes?: Array<{ type: string; headline: string }>;
  }>;
}

function logoFromLogos(logos: EspnTeamScheduleLogo[] | undefined, rel: string): string | undefined {
  return logos?.find((l) => l.rel.includes(rel))?.href;
}

// Same 1/2/3 preseason/regular/postseason convention every other ESPN
// endpoint in this codebase already uses (confirmed live: type:1 -> real
// "Preseason" games on this endpoint). "play-in-season" has no distinct
// numeric marker on this endpoint as far as could be checked - a play-in
// game here falls under type 3 same as the rest of the postseason, so it
// reads as a plain "Playoffs" label rather than the distinct "Play-In
// Tournament" text the day-based Games tab shows for the same game -
// acceptable rather than guessing at an unconfirmed 4th value.
function seasonSlugFromType(type: number | undefined): string | undefined {
  if (type === 1) return "preseason";
  if (type === 2) return "regular-season";
  if (type === 3) return "post-season";
  return undefined;
}

function toStandardEvent(raw: EspnTeamScheduleEvent): EspnEvent {
  return {
    id: raw.id,
    date: raw.date,
    season: raw.seasonType ? { type: raw.seasonType.type, slug: seasonSlugFromType(raw.seasonType.type) ?? "" } : undefined,
    competitions: raw.competitions.map((c) => ({
      status: c.status,
      competitors: c.competitors.map((comp) => ({
        id: comp.id,
        homeAway: comp.homeAway,
        score: comp.score ?? "0",
        team: {
          id: comp.team.id,
          name: comp.team.displayName,
          displayName: comp.team.displayName,
          // "scoreboard" is the same variant every other game tile in this
          // app already uses (teamLogos.ts's preferDarkLogoVariant then
          // picks light/dark from there) - "default" is the fallback for
          // the rare team missing that specific variant.
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
 * day-by-day across a date range, since a favorited team's games are sparse
 * relative to a full league slate. No client-side date windowing - whatever
 * ESPN's own endpoint currently returns for this team is shown as-is (no
 * artificial forward/backward cutoff).
 */
export async function fetchTeamSchedule(teamId: string, league: League): Promise<EspnEvent[]> {
  const data = await getJson<{ events?: EspnTeamScheduleEvent[] }>(`${basePath(league)}/teams/${teamId}/schedule`);
  return (data.events ?? []).map(toStandardEvent);
}

/** dateYyyymmdd must be in YYYYMMDD form (ESPN's scoreboard date format). */
export async function fetchScoreboard(dateYyyymmdd: string, league: League): Promise<EspnEvent[]> {
  const data = await getJson<{ events: EspnEvent[] }>(`${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`);
  return data.events ?? [];
}

export async function fetchSummary(eventId: string, league: League): Promise<EspnSummary> {
  return getJson<EspnSummary>(`${basePath(league)}/summary?event=${eventId}`);
}

/** The full real team roster for [league] (30 for NBA, etc.) - backs the favorite-teams search/browse screen, which has no other source for "every team's real name" (game data only ever carries whichever two teams happened to play). */
export async function fetchTeams(league: League): Promise<EspnTeam[]> {
  const data = await getJson<{ sports: Array<{ leagues: Array<{ teams: Array<{ team: EspnTeam }> }> }> }>(
    `${basePath(league)}/teams?limit=100`
  );
  return (data.sports?.[0]?.leagues?.[0]?.teams ?? []).map((t) => t.team);
}

export interface EspnRosterAthlete {
  id: string;
  displayName: string;
  headshot?: { href: string };
}

/** A team's current player list - backs the favorite-players search/browse screen's league -> team -> player drill-down. Basketball's roster endpoint carries a real headshot photo per athlete. */
export async function fetchRoster(teamId: string, league: League): Promise<EspnRosterAthlete[]> {
  const data = await getJson<{ athletes: EspnRosterAthlete[] }>(`${basePath(league)}/teams/${teamId}/roster`);
  return data.athletes ?? [];
}

/**
 * Generic sport-namespace-parameterized sibling of fetchTeams - basketball's
 * fetchTeams/basePath stay untouched (closed League union, basketball-only
 * URL namespace); this hits ESPN's same teams-list endpoint shape but for
 * any sport/league slug pair (e.g. "baseball"/"mlb", "football"/"nfl",
 * "hockey"/"nhl"), backing MLB/NFL/NHL's favorite-teams search/browse screen.
 */
export async function fetchTeamsForSport(sportSlug: string, league: string): Promise<EspnTeam[]> {
  const data = await getJson<{ sports: Array<{ leagues: Array<{ teams: Array<{ team: EspnTeam }> }> }> }>(
    `https://site.api.espn.com/apis/site/v2/sports/${sportSlug}/${league}/teams?limit=100`
  );
  return (data.sports?.[0]?.leagues?.[0]?.teams ?? []).map((t) => t.team);
}

// MLB's roster endpoint (confirmed directly against a real response) nests
// athletes inside position groups (athletes: [{ position, items: [...] }])
// rather than the flat array basketball's fetchRoster returns - this union
// lets fetchRosterForSport handle either shape without assuming which one
// a given sport uses.
interface EspnRosterGroup {
  position?: string;
  items?: EspnRosterAthlete[];
}

function isRosterGroup(entry: EspnRosterAthlete | EspnRosterGroup): entry is EspnRosterGroup {
  return Array.isArray((entry as EspnRosterGroup).items);
}

/**
 * Generic sport-namespace-parameterized sibling of fetchRoster - see
 * fetchTeamsForSport's comment. Backs MLB's favorite-players search/browse
 * screen (NFL/NHL rosters are out of scope for now).
 */
export async function fetchRosterForSport(sportSlug: string, league: string, teamId: string): Promise<EspnRosterAthlete[]> {
  const data = await getJson<{ athletes: Array<EspnRosterAthlete | EspnRosterGroup> }>(
    `https://site.api.espn.com/apis/site/v2/sports/${sportSlug}/${league}/teams/${teamId}/roster`
  );
  const raw = data.athletes ?? [];
  return raw.flatMap((entry) => (isRosterGroup(entry) ? entry.items ?? [] : [entry]));
}

/**
 * Every date [league] has at least one scheduled game, for whichever season
 * ESPN currently considers "current" as of [dateYyyymmdd] (a season already
 * over, with no next one announced yet, yields dates that are all in the
 * past - callers need to filter for dates after their own reference point).
 * One request returns the entire known season's dates (verified directly -
 * a single call returned 229 NBA dates spanning preseason through Finals),
 * so finding "the next date with a game" never means scanning day by day.
 */
export async function fetchCalendarDates(dateYyyymmdd: string, league: League): Promise<string[]> {
  const data = await getJson<{ leagues?: Array<{ calendar?: string[] }> }>(
    `${basePath(league)}/scoreboard?dates=${dateYyyymmdd}`
  );
  return (data.leagues?.[0]?.calendar ?? []).map((d) => d.slice(0, 10));
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

// Recurses arbitrarily deep, unlike fetchStandings' own single-level check -
// needed because fetchStandingsForSport's MLB caller (level=3 below) nests
// real per-team entries two levels down (league -> division), not one.
function groupsHaveEntries(groups: EspnStandingsGroup[]): boolean {
  return groups.some(
    (group) => (group.standings?.entries?.length ?? 0) > 0 || (group.children?.length ? groupsHaveEntries(group.children) : false)
  );
}

/**
 * Generic sport-namespace-parameterized sibling of fetchStandings - see
 * fetchTeamsForSport's comment. Confirmed directly against a real MLB
 * response (baseball/mlb/standings?season=2026): without &level=3, ESPN
 * returns just two flat groups (American League / National League, 15 teams
 * each, no division split at all). &level=3 is what actually produces the
 * real AL/NL -> East/Central/West nesting (same depth as basketball's
 * conference -> division), and the division-level entries carry the exact
 * same wins/losses/winPercent/gamesBehind/streak stat names basketball's
 * flattenGroups already looks for, so no basketball-side type or logic
 * changes were needed to reuse it for MLB.
 */
export async function fetchStandingsForSport(sportSlug: string, league: string, seasonYear: number): Promise<EspnStandingsRoot | null> {
  const data = await getJson<EspnStandingsRoot>(
    `https://site.api.espn.com/apis/v2/sports/${sportSlug}/${league}/standings?season=${seasonYear}&level=3`
  );
  return groupsHaveEntries(data.children ?? []) ? data : null;
}

export interface EspnLeaderEntry {
  // For basketball, this is already a clean per-category number (e.g.
  // "32.1" for points-per-game) - safe to show as-is. For MLB it is NOT:
  // confirmed directly against a real response that baseball's leaders
  // endpoint puts the player's entire season batting/pitching line here
  // (e.g. "130-388, 9 HR, 6 3B, 27 2B, 46 RBI, 61 R, 20 BB, 18 SB, 59 K"),
  // not a single value - statsService.ts's MLB path formats [value] itself
  // per category instead of using this field. Kept on the type since
  // basketball still reads it directly.
  displayValue: string;
  // The full-precision numeric stat (e.g. avg 0.3350515...) - present on
  // both sports' entries, but only actually consumed by statsService.ts's
  // MLB per-category formatter today (basketball uses displayValue).
  value?: number;
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

/**
 * Generic sport-namespace-parameterized sibling of fetchLeaders - see
 * fetchTeamsForSport's comment. Confirmed directly against a real MLB
 * response (baseball/mlb/seasons/2026/types/2/leaders): same top-level
 * shape (categories[].leaders[] with displayValue/athlete.$ref/team.$ref),
 * so EspnLeaderEntry/EspnLeaderCategory/EspnLeadersRoot and statsService.ts's
 * resolveLeader (which only ever walks $ref URLs generically) needed zero
 * changes to work for MLB - only the category name list differs (avg/
 * homeRuns/RBIs/ERA/strikeouts/wins for MLB vs. basketball's per-game
 * counting stats), which statsService.ts selects per sport.
 */
export async function fetchLeadersForSport(sportSlug: string, league: string, seasonYear: number): Promise<EspnLeadersRoot | null> {
  try {
    return await getJson<EspnLeadersRoot>(
      `https://sports.core.api.espn.com/v2/sports/${sportSlug}/leagues/${league}/seasons/${seasonYear}/types/2/leaders`
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

/**
 * Generic sport-namespace-parameterized sibling of fetchNews - see
 * fetchTeamsForSport's comment. Confirmed directly against a real MLB
 * response (baseball/mlb/news): identical article shape (id/headline/
 * description/published/images[]/links.web.href), so EspnNewsArticle needed
 * no changes to work for MLB.
 */
export async function fetchNewsForSport(sportSlug: string, league: string, limit: number): Promise<EspnNewsArticle[]> {
  const data = await getJson<{ articles?: EspnNewsArticle[] }>(
    `https://site.api.espn.com/apis/site/v2/sports/${sportSlug}/${league}/news?limit=${limit}`
  );
  return data.articles ?? [];
}
