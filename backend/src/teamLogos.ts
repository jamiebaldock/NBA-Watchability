// The historical backfill (backfillHistoricalWatchability.ts) only kept each
// team's ESPN displayName, not its logo URL - live schedule data gets logos
// straight off the ESPN scoreboard response, but historyService.ts has no
// live ESPN call to piggyback on for 2024-25/2025-26 games. Static map keyed
// off exactly the 30 displayName strings ESPN's own teams endpoint returns
// (verified against it directly, not typed from memory) - stable identifiers
// that don't change season to season.
const TEAM_ABBREVIATIONS: Record<string, string> = {
  "Atlanta Hawks": "atl",
  "Boston Celtics": "bos",
  "Brooklyn Nets": "bkn",
  "Charlotte Hornets": "cha",
  "Chicago Bulls": "chi",
  "Cleveland Cavaliers": "cle",
  "Dallas Mavericks": "dal",
  "Denver Nuggets": "den",
  "Detroit Pistons": "det",
  "Golden State Warriors": "gs",
  "Houston Rockets": "hou",
  "Indiana Pacers": "ind",
  "LA Clippers": "lac",
  "Los Angeles Lakers": "lal",
  "Memphis Grizzlies": "mem",
  "Miami Heat": "mia",
  "Milwaukee Bucks": "mil",
  "Minnesota Timberwolves": "min",
  "New Orleans Pelicans": "no",
  "New York Knicks": "ny",
  "Oklahoma City Thunder": "okc",
  "Orlando Magic": "orl",
  "Philadelphia 76ers": "phi",
  "Phoenix Suns": "phx",
  "Portland Trail Blazers": "por",
  "Sacramento Kings": "sac",
  "San Antonio Spurs": "sa",
  "Toronto Raptors": "tor",
  "Utah Jazz": "utah",
  "Washington Wizards": "wsh",
};

/**
 * Returns undefined for anything not in the 30-team map - covers the
 * handful of All-Star Weekend exhibition "teams" (Team Chuck, World, etc.)
 * that snuck into the backfill since they're not tagged preseason. None of
 * those clear the worth_your_time score threshold historyService.ts filters
 * on, so this only matters if that filter ever changes.
 */
export function teamLogoUrl(displayName: string): string | undefined {
  const abbr = TEAM_ABBREVIATIONS[displayName];
  return abbr ? `https://a.espncdn.com/i/teamlogos/nba/500/scoreboard/${abbr}.png` : undefined;
}
