import { EspnBoxscoreTeamPlayers, EspnEvent, EspnPlay, EspnSummary, League } from "./espnClient";
import { GameStatus, RubricInputs, StandoutPerformerJson, StarPerformance } from "./types";

/** ESPN clock is "mm:ss" above 1 minute, plain seconds ("4.0", "27.1") under it. */
export function parseClockSeconds(display: string): number {
  if (display.includes(":")) {
    const [m, s] = display.split(":").map(Number);
    return m * 60 + s;
  }
  return parseFloat(display);
}

export function mapEspnState(state: EspnEvent["competitions"][number]["status"]["type"]["state"]): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

type LeadState = "home" | "away" | "tie";

function leadStateAt(homeScore: number, awayScore: number): LeadState {
  if (homeScore > awayScore) return "home";
  if (awayScore > homeScore) return "away";
  return "tie";
}

export function countLeadChangesAndTies(plays: EspnPlay[]): number {
  let count = 0;
  let prev: LeadState | null = null;
  for (const play of plays) {
    const state = leadStateAt(play.homeScore, play.awayScore);
    if (prev !== null && state !== prev) count++;
    prev = state;
  }
  return count;
}

/** How far below the eventual winner ever fell, at its worst point. */
export function largestDeficitOvercome(plays: EspnPlay[], winnerHomeAway: "home" | "away"): number {
  let worst = 0;
  for (const play of plays) {
    const winnerScore = winnerHomeAway === "home" ? play.homeScore : play.awayScore;
    const loserScore = winnerHomeAway === "home" ? play.awayScore : play.homeScore;
    const deficit = loserScore - winnerScore;
    if (deficit > worst) worst = deficit;
  }
  return worst;
}

function finalPeriodOf(plays: EspnPlay[]): number {
  return plays.reduce((max, p) => Math.max(max, p.period.number), 0);
}

export function closeInFinalTwoMin(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  return plays.some(
    (p) =>
      p.period.number === finalPeriod &&
      parseClockSeconds(p.clock.displayValue) <= 120 &&
      Math.abs(p.homeScore - p.awayScore) <= 5
  );
}

export function leadChangeInFinalMin(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  let prev: LeadState | null = null;
  let changedInWindow = false;
  for (const play of plays) {
    const state = leadStateAt(play.homeScore, play.awayScore);
    const inWindow = play.period.number === finalPeriod && parseClockSeconds(play.clock.displayValue) <= 60;
    if (prev !== null && state !== prev && inWindow) changedInWindow = true;
    prev = state;
  }
  return changedInWindow;
}

/**
 * Heuristic (ESPN has no explicit "game-deciding play" flag): the last scoring
 * play, made within the final 24 seconds (one shot-clock) of regulation/OT,
 * itself flipped who was ahead (tie->lead or lead->lead-change) or broke a tie.
 * This also catches buzzer-beaters, since a game-winning shot by definition
 * changes the leader.
 */
export function decidedOnFinalPossession(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  const scoringPlays = plays.filter((p) => p.scoringPlay);
  if (scoringPlays.length === 0) return false;
  const last = scoringPlays[scoringPlays.length - 1];
  if (last.period.number !== finalPeriod) return false;
  if (parseClockSeconds(last.clock.displayValue) > 24) return false;

  const idx = plays.indexOf(last);
  const before = idx > 0 ? plays[idx - 1] : null;
  const stateBefore = before ? leadStateAt(before.homeScore, before.awayScore) : "tie";
  const stateAfter = leadStateAt(last.homeScore, last.awayScore);
  return stateBefore !== stateAfter;
}

/**
 * Game-winning field goal (not a free throw) in the final 3 seconds. A made
 * shot that late only counts if it actually flipped (or broke a tied) lead —
 * a make that merely cuts the final margin without changing the winner is
 * not a "game-winner" (e.g. a garbage-time three at the buzzer of a loss).
 */
export function isBuzzerBeater(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  const scoringPlays = plays.filter((p) => p.scoringPlay);
  if (scoringPlays.length === 0) return false;
  const last = scoringPlays[scoringPlays.length - 1];
  if (last.period.number !== finalPeriod || !last.shootingPlay) return false;
  if (parseClockSeconds(last.clock.displayValue) > 3) return false;

  const idx = plays.indexOf(last);
  const before = idx > 0 ? plays[idx - 1] : null;
  const stateBefore = before ? leadStateAt(before.homeScore, before.awayScore) : "tie";
  const stateAfter = leadStateAt(last.homeScore, last.awayScore);
  return stateBefore !== stateAfter;
}

export function overtimePeriodsFrom(plays: EspnPlay[]): number {
  return Math.max(0, finalPeriodOf(plays) - 4);
}

const STAT_CATEGORIES = ["PTS", "REB", "AST", "STL", "BLK"] as const;

// Point thresholds are calibrated per league, not universal: a 40-point game
// is unremarkable in the NBA but has happened only ~30 times in WNBA history
// (28 seasons) - using NBA thresholds on WNBA data would make "historic"/
// "great" nearly unreachable. Triple-double thresholds are left as-is (a
// 10-10-10 line is the same fixed statistical achievement in either league).
function starPointThresholds(league: League): { historic: number; great: number; good: number } {
  return league === "wnba"
    ? { historic: 40, great: 30, good: 25 }
    : { historic: 50, great: 40, good: 35 };
}

export function classifyStarPerformance(
  playersBoxscore: EspnBoxscoreTeamPlayers[] | undefined,
  league: League
): StarPerformance {
  if (!playersBoxscore || playersBoxscore.length === 0) return null;

  let maxPoints = 0;
  let anyTripleDouble = false;
  let anyNearTripleDouble = false;

  for (const team of playersBoxscore) {
    for (const statBlock of team.statistics) {
      const indices = STAT_CATEGORIES.map((cat) => statBlock.labels.indexOf(cat));
      for (const athlete of statBlock.athletes) {
        const values = indices.map((i) => (i >= 0 ? parseFloat(athlete.stats[i]) || 0 : 0));
        const [pts, reb, ast, stl, blk] = values;
        maxPoints = Math.max(maxPoints, pts);

        const doubleDigitCats = values.filter((v) => v >= 10).length;
        if (doubleDigitCats >= 3) anyTripleDouble = true;
        else if (doubleDigitCats >= 2 && values.some((v) => v >= 8 && v < 10)) anyNearTripleDouble = true;
      }
    }
  }

  const { historic, great, good } = starPointThresholds(league);
  if (maxPoints >= historic) return "historic";
  if (maxPoints >= great || anyTripleDouble) return "great";
  if (maxPoints >= good || anyNearTripleDouble) return "good";
  return null;
}

/**
 * Every player (on either team) whose own individual line clears the same
 * per-league "good"-or-better bar as classifyStarPerformance's tier - not
 * just the single top scorer, since a favorited-player callout needs to
 * check a specific person's line, and a losing team can still have a
 * standout performer on it. Same box-score scan as classifyStarPerformance
 * (kept separate rather than merged into it, since most callers only need
 * the tier and would otherwise pay for building a list they never use).
 */
export function findStandoutPerformers(
  playersBoxscore: EspnBoxscoreTeamPlayers[] | undefined,
  league: League
): StandoutPerformerJson[] {
  if (!playersBoxscore || playersBoxscore.length === 0) return [];

  const { good } = starPointThresholds(league);
  const performers: StandoutPerformerJson[] = [];

  for (const team of playersBoxscore) {
    for (const statBlock of team.statistics) {
      const indices = STAT_CATEGORIES.map((cat) => statBlock.labels.indexOf(cat));
      for (const athlete of statBlock.athletes) {
        const values = indices.map((i) => (i >= 0 ? parseFloat(athlete.stats[i]) || 0 : 0));
        const [pts, reb, ast] = values;
        const doubleDigitCats = values.filter((v) => v >= 10).length;
        const isTripleDouble = doubleDigitCats >= 3;

        if (pts >= good || isTripleDouble) {
          performers.push({
            name: athlete.athlete.displayName,
            line: isTripleDouble ? `${pts} PTS, ${reb} REB, ${ast} AST` : `${pts} PTS`,
            team: team.team.displayName
          });
        }
      }
    }
  }

  return performers;
}

/**
 * Which playoff round a post-season game belongs to, parsed from ESPN's own
 * notes headline text (e.g. "West 1st Round - Game 1", "East Semifinals -
 * Game 2", "East Finals - Game 1", "NBA Finals - Game 2", or WNBA's
 * conference-less equivalents "First Round - Game 2", "WNBA Semifinals -
 * Game 5", "WNBA Finals - Game 5") rather than a `series` object - the
 * headline text alone was sufficient to distinguish every round checked
 * directly against real ESPN data for both leagues' 2024-25 postseasons.
 * NBA's conference round names ("Conference Semifinals"/"Conference
 * Finals") only apply when the headline itself is conference-prefixed
 * ("East"/"West") - WNBA has no conference bracket, so its semifinals/
 * finals headlines (no East/West prefix) fall through to the plain
 * "Semifinals"/"Finals" names instead, matching WNBA's actual single-
 * bracket structure.
 */
function derivePlayoffRound(headline: string): string {
  const h = headline.toLowerCase();
  const conferenceRound = h.startsWith("east") || h.startsWith("west");
  if (h.includes("1st round") || h.includes("first round")) return "Playoffs: First Round";
  if (h.includes("semifinals")) return conferenceRound ? "Playoffs: Conference Semifinals" : "Playoffs: Semifinals";
  if (h.includes("finals")) return conferenceRound ? "Playoffs: Conference Finals" : "Playoffs: Finals";
  return "Playoffs";
}

/**
 * Season/tournament stage ("Regular Season", "Cup", "Playoffs: Conference
 * Semifinals", ...) driven by ESPN's own season.slug + competition notes,
 * not calendar-date guesswork - so preseason/regular-season/all-star/play-
 * in/playoffs and the NBA Cup (In-Season Tournament) / WNBA Commissioner's
 * Cup all label themselves correctly year-round with no manual updates as
 * the calendar moves. Only meant for the two real leagues - callers should
 * not call this for Summer League events, which keep their own separate,
 * static "NBA SUMMER LEAGUE" label instead.
 *
 * Verified directly against real ESPN scoreboard responses for every stage
 * this covers (rather than assumed): NBA Cup group play carries a notes
 * headline of exactly "NBA Cup - Group Play", while knockout games say
 * "NBA Cup - Quarterfinals"/"- Semifinals"/"NBA Cup Championship" - group
 * play is, for standings purposes, an ordinary regular-season game
 * (event.season.slug is "regular-season" for both), so only the knockout
 * headlines get their own "Cup" stage; group play falls through to the
 * normal slug-based "Regular Season" label below. WNBA's Commissioner's Cup
 * notes don't follow the same "- Group Play" convention (its group-stage
 * games are just headlined "WNBA Commissioner's Cup" with no qualifier), so
 * for WNBA every Cup-notes game - group and championship alike - gets the
 * "Cup" stage; this matches what ESPN itself distinguishes, not a guess.
 * All-Star games (e.g. "NBA All-Star - Championship", "AT&T WNBA All-Star
 * Game") carry season.slug "regular-season" like an ordinary game, so they
 * have to be caught by the notes headline before the slug switch below, the
 * same way the Cup check is. No calendar-date fallback is used anywhere
 * here: ESPN's own fields were sufficient for every case checked, and a
 * hardcoded date range would need updating every season anyway - exactly
 * what this is meant to avoid.
 */
export function deriveSeasonStage(event: EspnEvent): string | undefined {
  const notes = event.competitions[0]?.notes ?? [];
  if (notes.some((n) => n.headline?.toLowerCase().includes("all-star"))) return "All-Star";

  const cupNote = notes.find((n) => n.headline?.toLowerCase().includes("cup"));
  if (cupNote && !cupNote.headline.toLowerCase().includes("group play")) {
    return "Cup";
  }

  switch (event.season?.slug) {
    case "preseason":
      return "Preseason";
    case "regular-season":
      return "Regular Season";
    case "play-in-season":
      return "Play-In Tournament";
    case "post-season":
      return derivePlayoffRound(notes[0]?.headline ?? "");
    default:
      return undefined;
  }
}

/** "{LEAGUE} - {stage}" (e.g. "NBA - Playoffs: Conference Semifinals") - see deriveSeasonStage for the stage derivation itself. */
export function deriveCompetitionLabel(event: EspnEvent, league: "nba" | "wnba"): string | undefined {
  const stage = deriveSeasonStage(event);
  return stage ? `${league.toUpperCase()} - ${stage}` : undefined;
}

export interface MappedGame {
  away: string;
  home: string;
  status: GameStatus;
  tipoffUtc: string;
  rubric: RubricInputs;
}

export function mapEventToGame(event: EspnEvent, league: League, summary?: EspnSummary): MappedGame {
  const competition = event.competitions[0];
  const status = mapEspnState(competition.status.type.state);
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const plays = summary?.plays ?? [];
  const hasPlays = plays.length > 0;

  let rubric: RubricInputs;

  if (!hasPlays) {
    rubric = {
      status,
      period: status === "live" ? competition.status.period : undefined,
      clock: status === "live" ? competition.status.displayClock : undefined,
      overtimePeriods: 0,
      closeInFinalTwoMin: false,
      leadChangeInFinalMin: false,
      decidedOnFinalPossession: false,
      buzzerBeater: false,
      starPerformance: null,
    };
  } else {
    const winnerHomeAway: "home" | "away" =
      parseFloat(home.score) >= parseFloat(away.score) ? "home" : "away";

    rubric = {
      status,
      period: status === "live" ? competition.status.period : undefined,
      clock: status === "live" ? competition.status.displayClock : undefined,
      finalMargin: Math.abs(parseFloat(home.score) - parseFloat(away.score)),
      largestDeficitOvercome: largestDeficitOvercome(plays, winnerHomeAway),
      leadChanges: countLeadChangesAndTies(plays),
      overtimePeriods: overtimePeriodsFrom(plays),
      closeInFinalTwoMin: closeInFinalTwoMin(plays),
      leadChangeInFinalMin: leadChangeInFinalMin(plays),
      decidedOnFinalPossession: decidedOnFinalPossession(plays),
      buzzerBeater: isBuzzerBeater(plays),
      starPerformance: classifyStarPerformance(summary?.boxscore?.players, league),
      standoutPerformers: findStandoutPerformers(summary?.boxscore?.players, league),
    };
  }

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    status,
    tipoffUtc: event.date,
    rubric,
  };
}
