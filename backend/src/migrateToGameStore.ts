// Imports backend/data/historicalWatchability.json (the 2,650-game NBA
// backfill) and historicalWatchabilityWnba.json (the 576-game WNBA
// backfill, scored with the league-aware rubric from the start) into
// gameStore's durable games table - this is how each league's historical
// backfill "graduates" into the same permanent store live games use, per
// the user's lifecycle spec: a finished game from a year ago and one from
// five minutes ago are the same kind of row, not two systems.
//
// Idempotent (safe to run every time, not just once): upsertBaseEntry is
// INSERT OR IGNORE, and setFinalRubric only writes when score is still
// NULL, so running this against an already-migrated store is a harmless
// no-op. That's also why devServer.ts calls this on every startup rather
// than requiring a manual one-off run against production (which would need
// Render Shell access, a paid-tier feature) - a brand new empty disk
// self-seeds from this file automatically; an already-populated one just
// verifies nothing's missing.
//
// Can still be run standalone: npx tsx src/migrateToGameStore.ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { League } from "./espnClient";
import { FinalRubric, setFinalRubric, setMlbFinalRubric, setNflFinalRubric, setSeasonStageLabel, upsertBaseEntry } from "./gameStore";
import { COMPETITION_LABEL as MLB_COMPETITION_LABEL } from "./mlbGamesService";
import { computeMlbWatchabilityScore, MlbRubricInputs, tierForMlbScore } from "./mlbRubric";
import { deriveNflCompetitionLabel } from "./nflGamesService";
import { computeNflWatchabilityScore, NflRubricInputs, tierForNflScore } from "./nflRubric";
import { LeagueGroup, StarPerformance } from "./types";

interface HistoricalGame {
  eventId: string;
  season: string;
  date: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  largestDeficitOvercome: number;
  leadChanges: number;
  overtimePeriods: number;
  closeInFinalTwoMin: boolean;
  leadChangeInFinalMin: boolean;
  decidedOnFinalPossession: boolean;
  starPerformance: StarPerformance;
  buzzerBeater: boolean;
  score: number;
  tier: string;
}

const DATA_DIR = join(__dirname, "..", "data");

function migrateFile(fileName: string, league: League, leagueGroup: LeagueGroup): number {
  const path = join(DATA_DIR, fileName);
  const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: HistoricalGame[] };

  for (const g of games) {
    upsertBaseEntry({
      eventId: g.eventId,
      league,
      leagueGroup,
      away: g.away,
      home: g.home,
      tipoffUtc: g.date,
      status: "final",
    });

    const rubric: FinalRubric = {
      awayScore: g.awayScore,
      homeScore: g.homeScore,
      finalMargin: g.finalMargin,
      largestDeficitOvercome: g.largestDeficitOvercome,
      leadChanges: g.leadChanges,
      overtimePeriods: g.overtimePeriods,
      closeInFinalTwoMin: g.closeInFinalTwoMin,
      leadChangeInFinalMin: g.leadChangeInFinalMin,
      decidedOnFinalPossession: g.decidedOnFinalPossession,
      buzzerBeater: g.buzzerBeater,
      starPerformance: g.starPerformance,
      // The historical backfill predates player-name capture entirely (it
      // only ever recorded classifyStarPerformance's tier, not who drove
      // it) - a favorited-player callout simply won't retroactively appear
      // on these older rows, same as every other backfill-vs-live gap
      // already documented in this file.
      standoutPerformers: [],
      score: g.score,
      tier: g.tier,
    };
    // finalAt=null: this game's true end time isn't recorded anywhere in
    // the backfill, and stamping "whenever this migration happened to run"
    // would corrupt the per-league upload-lag stats getLagPercentiles
    // learns from. The highlights schedule falls back to tipoff_utc as its
    // anchor for any historical game that still needs a check.
    setFinalRubric(g.eventId, rubric, null);
  }

  return games.length;
}

// Raw per-game facts from backfillRawStatsMlb.ts's real 2025-season pull
// (backend/data/mlbRawStats.json) - gathered for rubric calibration, not
// gameStore seeding, so unlike HistoricalGame above this has no
// precomputed score/tier; those are computed fresh below via the same
// computeMlbWatchabilityScore/tierForMlbScore the live pipeline uses.
interface MlbHistoricalGame {
  eventId: string;
  date: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  totalRuns: number;
  extraInningsCount: number;
  largestDeficitOvercome: number;
  walkOff: boolean;
  combinedHomeRuns: number;
  maxHomeRunsByPlayer: number;
  noHitter: boolean;
  perfectGame: boolean;
  shutout: boolean;
  blownSave: boolean;
  combinedErrors: number;
}

// MLB's analogue of migrateFile above - a separate function rather than a
// generalized one because the source data has no precomputed score/tier
// (mlbRawStats.json predates the rubric itself) and the column set is
// narrower (setMlbFinalRubric, not setFinalRubric - see gameStore.ts's
// MlbFinalResult comment for why MLB doesn't get the full basketball-shaped
// column set). This is what actually fixes the Favorites tab's MLB gap: once
// a game's score/tier is cached here, getMlbTeamSchedule's processMlbEvent
// finds row.score already non-null and skips the live per-game
// fetchMlbSummary call entirely, so a newly-favorited team's ~90-100
// already-final games resolve from gameStore instantly instead of one slow
// sequential ESPN round-trip per game.
function migrateMlbFile(fileName: string): number {
  const path = join(DATA_DIR, fileName);
  const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: MlbHistoricalGame[] };

  for (const g of games) {
    upsertBaseEntry({
      eventId: g.eventId,
      league: "mlb",
      leagueGroup: "mlb",
      away: g.away,
      home: g.home,
      tipoffUtc: g.date,
      status: "final",
    });
    setSeasonStageLabel(g.eventId, MLB_COMPETITION_LABEL);

    const rubricInputs: MlbRubricInputs = {
      finalMargin: g.finalMargin,
      totalRuns: g.totalRuns,
      largestDeficitOvercome: g.largestDeficitOvercome,
      walkOff: g.walkOff,
      extraInningsCount: g.extraInningsCount,
      combinedHomeRuns: g.combinedHomeRuns,
      maxHomeRunsByPlayer: g.maxHomeRunsByPlayer,
      teamBlanked: g.shutout,
      noHitter: g.noHitter,
      perfectGame: g.perfectGame,
      blownSave: g.blownSave,
      combinedErrors: g.combinedErrors,
    };
    // No stakes for historical rows, same reason the basketball migration
    // above never calls setPreview for backfilled games - stakes comes from
    // an LLM call keyed on real-time context a season-old game doesn't have.
    const score = computeMlbWatchabilityScore(rubricInputs, undefined).total;

    setMlbFinalRubric(
      g.eventId,
      {
        awayScore: g.awayScore,
        homeScore: g.homeScore,
        score,
        tier: tierForMlbScore(score),
        // Predates player-name capture (backfillRawStatsMlb.ts only recorded
        // the max-HR *count*, not who hit them) - same gap documented on the
        // basketball migration above.
        standoutPerformers: [],
        finalMargin: g.finalMargin,
        largestDeficitOvercome: g.largestDeficitOvercome,
        rubricInputs,
      },
      null
    );
  }

  return games.length;
}

// Raw per-game facts from backfillRawStatsNfl.ts's real 286-game 2025-season
// pull (backend/data/nflRawStats.json) - same "no precomputed score/tier,
// compute fresh via the live rubric functions" shape as MlbHistoricalGame
// above.
interface NflHistoricalGame {
  eventId: string;
  date: string;
  // Needed to derive the exact same real per-round season_stage_label the
  // live pipeline does (deriveNflCompetitionLabel) instead of every
  // backfilled game getting the same generic label regardless of
  // postseason status - see that function's own comment for why this
  // matters (it's what lets gameStore.ts's getMostRecentFinalsEnd actually
  // find the Super Bowl and correctly close out "This season").
  seasonType: number;
  weekNumber?: number;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  totalPoints: number;
  leadChanges: number;
  largestDeficitOvercome: number;
  decisiveScoreLate: boolean;
  combinedTurnovers: number;
  defensiveOrSpecialTeamsTd: boolean;
  maxPassingYards: number;
  maxRushingYards: number;
  maxTotalTdsByPlayer: number;
  overtimePeriods: number;
}

// NFL's analogue of migrateMlbFile above - this is what closes the same
// Favorites-tab cold-cache gap MLB's own migration closed: once a game's
// score/tier is cached here, getNflTeamSchedule's processNflEvent finds
// row.score already non-null and skips the live per-game fetchNflSummary
// call entirely. Done from day one for NFL (not retrofitted after a bug
// report, unlike MLB - see feedback_new_league_full_pipeline_checklist.md).
function migrateNflFile(fileName: string): number {
  const path = join(DATA_DIR, fileName);
  const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: NflHistoricalGame[] };

  for (const g of games) {
    upsertBaseEntry({
      eventId: g.eventId,
      league: "nfl",
      leagueGroup: "nfl",
      away: g.away,
      home: g.home,
      tipoffUtc: g.date,
      status: "final",
    });
    setSeasonStageLabel(g.eventId, deriveNflCompetitionLabel(g.seasonType, g.weekNumber));

    const rubricInputs: NflRubricInputs = {
      finalMargin: g.finalMargin,
      largestDeficitOvercome: g.largestDeficitOvercome,
      leadChanges: g.leadChanges,
      overtimePeriods: g.overtimePeriods,
      decisiveScoreLate: g.decisiveScoreLate,
      combinedTurnovers: g.combinedTurnovers,
      defensiveOrSpecialTeamsTd: g.defensiveOrSpecialTeamsTd,
      maxPassingYards: g.maxPassingYards,
      maxRushingYards: g.maxRushingYards,
      maxTotalTdsByPlayer: g.maxTotalTdsByPlayer,
      totalPoints: g.totalPoints,
    };
    // No stakes for historical rows, same reasoning as the NBA/MLB migrations above.
    const score = computeNflWatchabilityScore(rubricInputs, undefined).total;

    setNflFinalRubric(
      g.eventId,
      {
        awayScore: g.awayScore,
        homeScore: g.homeScore,
        score,
        tier: tierForNflScore(score),
        // Predates player-name capture (backfillRawStatsNfl.ts only recorded
        // yardage/TD counts, not who drove them) - same gap documented on
        // the NBA/MLB migrations above.
        standoutPerformers: [],
        finalMargin: g.finalMargin,
        largestDeficitOvercome: g.largestDeficitOvercome,
        rubricInputs,
      },
      null
    );
  }

  return games.length;
}

export function migrateHistoricalBackfill(): void {
  const nbaCount = migrateFile("historicalWatchability.json", "nba", "nba");
  const wnbaCount = migrateFile("historicalWatchabilityWnba.json", "wnba", "wnba");
  const mlbCount = migrateMlbFile("mlbRawStats.json");
  const nflCount = migrateNflFile("nflRawStats.json");
  console.log(
    `migrateHistoricalBackfill: verified ${nbaCount} NBA, ${wnbaCount} WNBA, ${mlbCount} MLB, and ${nflCount} NFL historical games are present in gameStore.`
  );
}

// Allows `npx tsx src/migrateToGameStore.ts` to still work standalone for
// local testing, without running twice when devServer.ts imports this module.
if (require.main === module) {
  migrateHistoricalBackfill();
}
