// Mirrors the mobile client's Game data class (mobile/app/.../data/Game.kt) and
// the JSON contract in nba-watchability-spec.md section 5. Keep field names in sync.

export type GameStatus = "final" | "live" | "upcoming";

export type StarPerformance = "historic" | "great" | "good" | null;

// Which mutually-exclusive slate the client is currently viewing (settings
// toggle + top-left dropdown). Distinct from espnClient.ts's League - a
// LeagueGroup determines which underlying ESPN leagues get unioned together
// for a request (gamesService.ts's LEAGUE_GROUPS), never both at once. "epl"/
// "la-liga" are soccer, routed to an entirely separate ESPN client/mapper/
// rubric (soccerEspnClient.ts/soccerGameMapper.ts/soccerRubric.ts) - see
// SPORT_FOR_LEAGUE_GROUP below for the dispatch point.
export type LeagueGroup = "nba" | "wnba" | "epl" | "la-liga";

export type Sport = "basketball" | "soccer";

// The single choke point every sport-dispatching call site (httpHandler.ts's
// getSchedule/getNextGameDateForLeagueGroup/getSeasonWindowForLeagueGroup,
// gamesService.ts's highlights-eligibility guard) checks before deciding
// whether to run the basketball pipeline (gamesService.ts/gameMapper.ts/
// rubric.ts) or the soccer one (soccerGamesService.ts/soccerGameMapper.ts/
// soccerRubric.ts) for a given leagueGroup - adding a third sport later means
// adding one entry here, not hunting down every place that currently assumes
// only two.
export const SPORT_FOR_LEAGUE_GROUP: Record<LeagueGroup, Sport> = {
  nba: "basketball",
  wnba: "basketball",
  epl: "soccer",
  "la-liga": "soccer"
};

export interface GameJson {
  a: string;
  h: string;
  al?: string;
  hl?: string;
  stt: GameStatus;
  utc: string;
  lg: "nba" | "wnba" | "summer" | "soccer";
  cl?: string;
  q?: number;
  clk?: string;
  m?: number;
  cb?: number;
  lc?: number;
  // Absolute final score - only ever populated by historyService.ts. Every
  // other tab deliberately never sends these (spec section 2 point 8: never
  // reveal the winner or final score for a game the viewer might not have
  // watched yet), but a game the user is intentionally browsing in the
  // History tab has nothing left to spoil, so showing it plainly is the
  // point rather than a leak.
  as?: number;
  hs?: number;
  ot: number;
  c5: boolean;
  lcf: boolean;
  fp: boolean;
  bz: boolean;
  st: StarPerformance;
  sk?: number;
  hook: string;
  pitch?: string;
  score?: number;
  score_visible: boolean;
  yt?: string;
}

export interface StandingsTeamJson {
  id: string;
  n: string;
  ab: string;
  lg?: string;
  w: number;
  l: number;
  pct: string;
  gb: string;
  strk?: string;
}

export interface StandingsGroupJson {
  name: string;
  teams: StandingsTeamJson[];
}

export interface StandingsResponseJson {
  season: string;
  groups: StandingsGroupJson[];
}

export interface StatLeaderJson {
  name: string;
  team: string;
  teamLogo?: string;
  value: string;
}

export interface StatCategoryJson {
  key: string;
  label: string;
  abbr: string;
  leaders: StatLeaderJson[];
}

export interface StatsResponseJson {
  season: string;
  categories: StatCategoryJson[];
}

export interface NewsArticleJson {
  id: number;
  headline: string;
  description?: string;
  image?: string;
  link?: string;
  published: string;
}

export interface NewsResponseJson {
  articles: NewsArticleJson[];
}

// Backs the favorite-teams search/browse screen - the real per-league team
// roster (name + logo), not anything derivable from game data alone (a
// schedule only ever carries whichever two teams happened to play).
export interface TeamJson {
  name: string;
  logo?: string;
}

export interface TeamsResponseJson {
  teams: TeamJson[];
}

// Raw rubric inputs derived from play-by-play + box score, before the LLM
// fields (hook/stakes) and before the score_visible gating rule are applied.
export interface RubricInputs {
  status: GameStatus;
  period?: number;
  clock?: string;
  finalMargin?: number;
  largestDeficitOvercome?: number;
  leadChanges?: number;
  overtimePeriods: number;
  closeInFinalTwoMin: boolean;
  leadChangeInFinalMin: boolean;
  decidedOnFinalPossession: boolean;
  buzzerBeater: boolean;
  starPerformance: StarPerformance;
}

export interface ScoredGame extends RubricInputs {
  score?: number;
  scoreVisible: boolean;
}
