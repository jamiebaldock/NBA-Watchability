// Maps an NBA/Summer League game to the IANA timezone of wherever it's
// actually being played, so "5am local" (gamesService.ts's pregame-preview
// generation gate) means 5am at the venue - not UTC, not the user's device.
// ESPN's venue data gives a city name but no timezone field directly, so this
// is a small hardcoded city -> zone table covering every NBA arena city plus
// the Summer League host cities (Las Vegas, Salt Lake City, Sacramento -
// already covered by their NBA teams' own entries).
import { EspnEvent } from "./espnClient";

const CITY_TIMEZONES: Record<string, string> = {
  Atlanta: "America/New_York",
  Boston: "America/New_York",
  Brooklyn: "America/New_York",
  Charlotte: "America/New_York",
  Chicago: "America/Chicago",
  Cleveland: "America/New_York",
  Dallas: "America/Chicago",
  Denver: "America/Denver",
  Detroit: "America/New_York",
  "San Francisco": "America/Los_Angeles",
  Houston: "America/Chicago",
  Indianapolis: "America/New_York",
  "Los Angeles": "America/Los_Angeles",
  Inglewood: "America/Los_Angeles", // Clippers' Intuit Dome
  Memphis: "America/Chicago",
  Miami: "America/New_York",
  Milwaukee: "America/Chicago",
  Minneapolis: "America/Chicago",
  "New Orleans": "America/Chicago",
  "New York": "America/New_York",
  "Oklahoma City": "America/Chicago",
  Orlando: "America/New_York",
  Philadelphia: "America/New_York",
  Phoenix: "America/Phoenix",
  Portland: "America/Los_Angeles",
  Sacramento: "America/Los_Angeles",
  "San Antonio": "America/Chicago",
  "Salt Lake City": "America/Denver",
  Toronto: "America/Toronto",
  Washington: "America/New_York",
  "Las Vegas": "America/Los_Angeles",
};

// Fallback keyed by home team display name, for the rare case venue city data
// is missing from ESPN's response.
const TEAM_TIMEZONES: Record<string, string> = {
  "Atlanta Hawks": "America/New_York",
  "Boston Celtics": "America/New_York",
  "Brooklyn Nets": "America/New_York",
  "Charlotte Hornets": "America/New_York",
  "Chicago Bulls": "America/Chicago",
  "Cleveland Cavaliers": "America/New_York",
  "Dallas Mavericks": "America/Chicago",
  "Denver Nuggets": "America/Denver",
  "Detroit Pistons": "America/New_York",
  "Golden State Warriors": "America/Los_Angeles",
  "Houston Rockets": "America/Chicago",
  "Indiana Pacers": "America/New_York",
  "LA Clippers": "America/Los_Angeles",
  "Los Angeles Lakers": "America/Los_Angeles",
  "Memphis Grizzlies": "America/Chicago",
  "Miami Heat": "America/New_York",
  "Milwaukee Bucks": "America/Chicago",
  "Minnesota Timberwolves": "America/Chicago",
  "New Orleans Pelicans": "America/Chicago",
  "New York Knicks": "America/New_York",
  "Oklahoma City Thunder": "America/Chicago",
  "Orlando Magic": "America/New_York",
  "Philadelphia 76ers": "America/New_York",
  "Phoenix Suns": "America/Phoenix",
  "Portland Trail Blazers": "America/Los_Angeles",
  "Sacramento Kings": "America/Los_Angeles",
  "San Antonio Spurs": "America/Chicago",
  "Toronto Raptors": "America/Toronto",
  "Utah Jazz": "America/Denver",
  "Washington Wizards": "America/New_York",
};

const DEFAULT_TIME_ZONE = "America/New_York";

export function venueTimeZone(event: EspnEvent, homeTeamDisplayName: string): string {
  const city = event.competitions[0]?.venue?.address?.city;
  if (city && CITY_TIMEZONES[city]) return CITY_TIMEZONES[city];
  if (TEAM_TIMEZONES[homeTeamDisplayName]) return TEAM_TIMEZONES[homeTeamDisplayName];
  return DEFAULT_TIME_ZONE;
}

/** Offset (in minutes) of `timeZone` from UTC at `instant`, via the standard Intl round-trip trick. */
function tzOffsetMinutes(instant: Date, timeZone: string): number {
  const tzDate = new Date(instant.toLocaleString("en-US", { timeZone }));
  const utcDate = new Date(instant.toLocaleString("en-US", { timeZone: "UTC" }));
  return (tzDate.getTime() - utcDate.getTime()) / 60000;
}

/**
 * Whether it's currently at or past 5:00 AM on the game's own calendar date,
 * as measured at the venue (not UTC, not the caller's timezone). Since tip-off
 * itself always happens well after 5am local on that same date, this is only
 * ever false while a game is still "upcoming" and it's the early hours of its
 * own game day (or earlier) at the venue.
 */
export function hasLocalFiveAmPassed(tipoffUtc: string, timeZone: string, now: Date = new Date()): boolean {
  const tipoff = new Date(tipoffUtc);
  const localDateStr = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(tipoff);
  const [y, m, d] = localDateStr.split("-").map(Number);
  const naiveFiveAmUtc = new Date(Date.UTC(y, m - 1, d, 5, 0, 0));
  const offsetMinutes = tzOffsetMinutes(naiveFiveAmUtc, timeZone);
  const fiveAmInstant = new Date(naiveFiveAmUtc.getTime() - offsetMinutes * 60000);
  return now.getTime() >= fiveAmInstant.getTime();
}
