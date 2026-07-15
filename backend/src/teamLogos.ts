// The original historical backfill only kept each team's ESPN displayName,
// not its logo URL - live schedule data gets logos
// straight off the ESPN scoreboard response, but historyService.ts has no
// live ESPN call to piggyback on for backfilled games. Static maps keyed
// off exactly the displayName strings ESPN's own teams endpoint returns
// (verified against it directly, not typed from memory) - stable identifiers
// that don't change season to season.
const NBA_TEAM_ABBREVIATIONS: Record<string, string> = {
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

// All 15 current WNBA teams (including the Golden State Valkyries' 2025
// expansion debut and the Portland Fire/Toronto Tempo 2026 expansion) -
// note WNBA logos live at a different URL shape than NBA's (no
// "/scoreboard" path segment), confirmed directly against ESPN's teams
// endpoint per team, not assumed from the NBA pattern.
const WNBA_TEAM_ABBREVIATIONS: Record<string, string> = {
  "Atlanta Dream": "atl",
  "Chicago Sky": "chi",
  "Connecticut Sun": "con",
  "Dallas Wings": "dal",
  "Golden State Valkyries": "gs",
  "Indiana Fever": "ind",
  "Las Vegas Aces": "lv",
  "Los Angeles Sparks": "la",
  "Minnesota Lynx": "min",
  "New York Liberty": "ny",
  "Phoenix Mercury": "phx",
  "Portland Fire": "por",
  "Seattle Storm": "sea",
  "Toronto Tempo": "tor",
  "Washington Mystics": "wsh",
};

/**
 * Returns undefined for anything not in the relevant league's team map -
 * for NBA, covers the handful of All-Star Weekend exhibition "teams" (Team
 * Chuck, World, etc.) that snuck into the backfill since they're not
 * tagged preseason; none of those clear the worth_your_time score
 * threshold historyService.ts filters on, so this only matters if that
 * filter ever changes.
 */
export function teamLogoUrl(displayName: string, leagueGroup: "nba" | "wnba" = "nba"): string | undefined {
  if (leagueGroup === "wnba") {
    const abbr = WNBA_TEAM_ABBREVIATIONS[displayName];
    return abbr ? `https://a.espncdn.com/i/teamlogos/wnba/500/${abbr}.png` : undefined;
  }
  const abbr = NBA_TEAM_ABBREVIATIONS[displayName];
  return abbr ? `https://a.espncdn.com/i/teamlogos/nba/500/scoreboard/${abbr}.png` : undefined;
}
