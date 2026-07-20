// Mirrors the mobile client's Game data class (mobile/app/.../data/Game.kt) and
// the JSON contract in nba-watchability-spec.md section 5. Keep field names in sync.

export type GameStatus = "final" | "live" | "upcoming";

export type StarPerformance = "historic" | "great" | "good" | null;

// Which mutually-exclusive slate the client is currently viewing (settings
// toggle + top-left dropdown). Distinct from espnClient.ts's League - a
// LeagueGroup determines which underlying ESPN leagues get unioned together
// for a request (gamesService.ts's LEAGUE_GROUPS), never both at once.
// "mlb" is Games-tab-only for now (James's explicit call) - see
// mlbGamesService.ts's file comment for exactly what's not wired up yet
// (team schedules, season-window/History, per-game rubric-input persistence
// beyond score/tier).
export type LeagueGroup = "nba" | "wnba" | "mlb" | "nfl" | "nhl";

export type Sport = "basketball" | "baseball" | "football" | "hockey";

// The single choke point every sport-dispatching call site (httpHandler.ts's
// getSchedule/getNextGameDateForLeagueGroup/getSeasonWindowForLeagueGroup,
// gamesService.ts's highlights-eligibility guard) checks before deciding
// which per-sport pipeline (gamesService.ts/gameMapper.ts/rubric.ts for
// basketball, mlbGamesService.ts/mlbGameMapper.ts/mlbRubric.ts for baseball)
// to run for a given leagueGroup - adding another sport later means adding
// one entry here, not hunting down every place that currently assumes only
// two.
export const SPORT_FOR_LEAGUE_GROUP: Record<LeagueGroup, Sport> = {
  nba: "basketball",
  wnba: "basketball",
  mlb: "baseball",
  nfl: "football",
  nhl: "hockey"
};

export interface GameJson {
  // ESPN's own event id - not used for the client's own identity/dedup
  // logic (Game.id in Game.kt is still the away@home@utc composite key,
  // unrelated to this), only as the lookup key for the on-demand
  // game-detail popup's /game-detail?eventId= endpoint (Phase G).
  id?: string;
  a: string;
  h: string;
  al?: string;
  hl?: string;
  stt: GameStatus;
  utc: string;
  lg: "nba" | "wnba" | "summer" | "mlb";
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
  // Every player (either team) whose individual line cleared the same
  // per-league "good"-or-better bar rubric.ts's star-performance tier
  // already uses (rather than just the single top scorer) - a favorited-
  // player callout needs to check "did *this specific person* have a
  // notable game", not just "who had the best game overall", and a blowout
  // can have a standout performer on the losing side too.
  sop?: StandoutPerformerJson[];
  sk?: number;
  hook: string;
  pitch?: string;
  score?: number;
  score_visible: boolean;
  yt?: string;
  // MLB-only raw rubric facts (mlbRubric.ts's MlbRubricInputs) - backs the
  // game-detail popup's Breakdown tab, which otherwise has nothing
  // MLB-shaped to show (every other GameJson field above - ot/c5/lcf/fp/bz/
  // st - is basketball-specific and always false/null/0 for MLB games).
  // Mirrors the existing NBA/WNBA pattern of sending raw facts and letting
  // the client recompute per-category points (Rubric.kt), rather than
  // sending pre-computed points, since that also gets MLB weight-adjustable
  // sliders "for free" whenever that's built.
  mlbInputs?: MlbRubricInputsJson;
}

export interface MlbRubricInputsJson {
  finalMargin: number;
  totalRuns: number;
  largestDeficitOvercome: number;
  walkOff: boolean;
  extraInningsCount: number;
  combinedHomeRuns: number;
  maxHomeRunsByPlayer: number;
  teamBlanked: boolean;
  noHitter: boolean;
  perfectGame: boolean;
  blownSave: boolean;
  combinedErrors: number;
}

export interface StandoutPerformerJson {
  name: string;
  line: string;
  // Which of the game's two teams this player is on (display name, matching
  // GameJson's own a/h fields) - optional since any row persisted before this
  // field existed decodes without it. Needed so a tile's long-press quick-add
  // (mobile GameCard.kt) can tag a favorited player with a team, the same way
  // every other favorite-player entry point already does.
  team?: string;
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
  // ESPN's own numeric team id - needed to query the /roster endpoint for
  // this team. Absent only in theory (every real team ESPN returns has one).
  id: string;
  name: string;
  logo?: string;
}

export interface PlayerJson {
  id: string;
  name: string;
  // Real headshot photo URL - only ESPN's basketball roster endpoint carries
  // one; undefined here means "no photo available," not "not yet fetched."
  headshot?: string;
}

export interface RosterResponseJson {
  players: PlayerJson[];
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
  standoutPerformers?: StandoutPerformerJson[];
}

// Backs the game-detail popup (Phase G) - fetched fresh on-demand when a
// user taps a specific finished tile, not persisted, since this is a rare
// per-tap action rather than something every game needs computed up front.
export interface TopPerformerJson {
  name: string;
  team: string;
  line: string;
}

export interface HeadToHeadGameJson {
  eventId: string;
  utc: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
}

// Deliberately simplified from a true "clinches playoff spot" computation
// (division/conference tiebreakers and magic-number math aren't something
// ESPN's standings response hands over directly) - just each team's current
// rank/record/group, which is still useful context and derivable from data
// this app already fetches (standingsService.ts) rather than needing new
// ESPN plumbing.
export interface TeamStandingsContextJson {
  rank?: number;
  record?: string;
  groupName?: string;
}

export interface GameDetailResponseJson {
  topPerformers: TopPerformerJson[];
  headToHead: HeadToHeadGameJson[];
  awayStandings: TeamStandingsContextJson;
  homeStandings: TeamStandingsContextJson;
}

export interface ScoredGame extends RubricInputs {
  score?: number;
  scoreVisible: boolean;
}
