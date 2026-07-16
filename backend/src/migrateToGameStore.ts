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
import { FinalRubric, setFinalRubric, setSeasonStageLabel, setSoccerFinalRubric, upsertBaseEntry } from "./gameStore";
import { LEAGUE_DISPLAY_NAME, SOCCER_LEAGUE_FOR_GROUP, SoccerLeagueGroup } from "./soccerGamesService";
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

interface HistoricalSoccerGame {
  eventId: string;
  season: string;
  date: string;
  away: string;
  home: string;
  awayLogo?: string;
  homeLogo?: string;
  awayScore: number;
  homeScore: number;
  score: number;
  tier: string;
}

/**
 * Soccer's equivalent of migrateFile above - separate rather than a shared
 * function since soccer rows use setSoccerFinalRubric (only score/tier/
 * scores - no basketball-shaped rubric columns to fill in) and always get
 * a season_stage_label written directly here (soccer has no per-game stage
 * variation to derive - every eng.1/esp.1 match is "Regular Season" - so
 * there's no need for a separate migrateStageLabels.ts-style enrichment
 * pass the way basketball needed one).
 */
function migrateSoccerFile(fileName: string, leagueGroup: SoccerLeagueGroup): number {
  const path = join(DATA_DIR, fileName);
  const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: HistoricalSoccerGame[] };
  const league = SOCCER_LEAGUE_FOR_GROUP[leagueGroup];
  const competitionLabel = `${LEAGUE_DISPLAY_NAME[leagueGroup]} - Regular Season`;

  for (const g of games) {
    upsertBaseEntry({
      eventId: g.eventId,
      league,
      leagueGroup,
      away: g.away,
      home: g.home,
      awayLogo: g.awayLogo,
      homeLogo: g.homeLogo,
      tipoffUtc: g.date,
      status: "final",
    });
    setSeasonStageLabel(g.eventId, competitionLabel);
    // finalAt=null for the same reason as the basketball migration above -
    // a backfilled match's true full-time timestamp isn't tracked, and
    // stamping "whenever this migration ran" would corrupt the highlights
    // upload-lag stats (moot for soccer today - no highlights search is
    // configured for either league yet - but keeping the same convention
    // costs nothing and avoids a surprise if that changes later).
    setSoccerFinalRubric(
      g.eventId,
      // Same gap as the basketball backfill above - this migration predates
      // standout-scorer capture, so these rows just get an empty list.
      { awayScore: g.awayScore, homeScore: g.homeScore, score: g.score, tier: g.tier, standoutPerformers: [] },
      null
    );
  }

  return games.length;
}

export function migrateHistoricalBackfill(): void {
  const nbaCount = migrateFile("historicalWatchability.json", "nba", "nba");
  const wnbaCount = migrateFile("historicalWatchabilityWnba.json", "wnba", "wnba");
  const eplCount = migrateSoccerFile("historicalWatchabilityEpl.json", "epl");
  const laLigaCount = migrateSoccerFile("historicalWatchabilityLaLiga.json", "la-liga");
  console.log(
    `migrateHistoricalBackfill: verified ${nbaCount} NBA, ${wnbaCount} WNBA, ${eplCount} EPL, and ${laLigaCount} La Liga historical games are present in gameStore.`
  );
}

// Allows `npx tsx src/migrateToGameStore.ts` to still work standalone for
// local testing, without running twice when devServer.ts imports this module.
if (require.main === module) {
  migrateHistoricalBackfill();
}
