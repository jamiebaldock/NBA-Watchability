// Free, keyless injury data from ESPN's core API (confirmed working:
// sports.core.api.espn.com/.../teams/{id}/injuries returns real, current
// structured injury reports - status, comment, expected return - even for
// Summer League team IDs, since Summer League rosters are filed under the
// same parent NBA franchise team ID). Used once per game, at pregame-preview
// generation time (gamesService.ts), to give the LLM real current context
// instead of just static team names/records.
//
// WNBA teams are entirely separate franchises under their own core-API league
// segment - reusing the NBA path with a WNBA team ID would silently 404 (this
// fetch is best-effort and swallows errors, so it wouldn't crash, just quietly
// return no injury context for every WNBA game).
import { League } from "./espnClient";

function coreLeagueSegment(league: League): "nba" | "wnba" {
  return league === "wnba" ? "wnba" : "nba";
}

const MAX_INJURIES_PER_TEAM = 3;

interface EspnRefList {
  items: Array<{ $ref: string }>;
}

interface EspnInjuryRecord {
  status: string;
  shortComment?: string;
  athlete: { $ref: string };
}

interface EspnAthleteRef {
  displayName: string;
}

export interface InjuryNote {
  athleteName: string;
  status: string;
  comment?: string;
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`ESPN core API request failed: ${res.status} ${res.statusText} (${url})`);
  return (await res.json()) as T;
}

/** Best-effort: a broken/slow injuries fetch should never block pregame-preview generation. */
export async function fetchTeamInjuries(teamId: string, league: League): Promise<InjuryNote[]> {
  try {
    const coreBase = `https://sports.core.api.espn.com/v2/sports/basketball/leagues/${coreLeagueSegment(league)}`;
    const list = await getJson<EspnRefList>(`${coreBase}/teams/${teamId}/injuries`);
    const records = await Promise.all(
      list.items.slice(0, MAX_INJURIES_PER_TEAM).map((item) =>
        getJson<EspnInjuryRecord>(item.$ref).catch(() => null)
      )
    );

    const notes: InjuryNote[] = [];
    for (const record of records) {
      if (!record) continue;
      const athlete = await getJson<EspnAthleteRef>(record.athlete.$ref).catch(() => null);
      if (!athlete) continue;
      notes.push({ athleteName: athlete.displayName, status: record.status, comment: record.shortComment });
    }
    return notes;
  } catch {
    return [];
  }
}

export function injuryNotesToText(teamName: string, notes: InjuryNote[]): string | null {
  if (notes.length === 0) return null;
  const parts = notes.map((n) => `${n.athleteName} (${n.status}${n.comment ? `: ${n.comment}` : ""})`);
  return `${teamName}: ${parts.join("; ")}`;
}
