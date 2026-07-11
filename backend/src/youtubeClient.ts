// Matches a finished game to its official "FULL GAME HIGHLIGHTS" video on
// the NBA's own YouTube channel. Channel ID confirmed via the canonical link
// on youtube.com/@NBA (not to be confused with unofficial reuploader
// channels/playlists that reuse the same "FULL GAME HIGHLIGHTS" phrase).
const NBA_YOUTUBE_CHANNEL_ID = "UCWJ2lWNubArHWmf3FIHbfcQ";

// search.list has its own dedicated daily quota bucket (separate from the
// general 10,000-unit pool), hard-capped at 100 calls/day as of Google's
// June 2026 quota change - this is only safe because gamesService.ts calls
// it once per game ever, caching the result forever (cache.ts's ytChecked
// flag), never re-searching on every request.
const YOUTUBE_SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

interface YoutubeSearchItem {
  id?: { videoId?: string };
  snippet?: { title?: string };
}

interface YoutubeSearchResponse {
  items?: YoutubeSearchItem[];
}

export interface HighlightsMatch {
  videoId: string;
  title: string;
}

function getApiKey(): string | undefined {
  return process.env.YOUTUBE_API_KEY;
}

/** Lets callers distinguish "no key configured yet" (don't mark as checked - retry once one's added) from "searched, found nothing" (safe to mark permanently). */
export function isYoutubeSearchConfigured(): boolean {
  return Boolean(getApiKey());
}

/**
 * NBA's official titles use just the team nickname ("HORNETS at JAZZ"), not
 * the full "city + nickname" display name - the last word of the display
 * name matches this for every team except "Trail Blazers", where "Blazers"
 * alone is still a safe substring match against either spelling.
 */
function teamNickname(displayName: string): string {
  return displayName.trim().split(/\s+/).pop() ?? displayName;
}

/**
 * Searches the NBA's channel for this game's full-game-highlights video,
 * scoped to a tight window around the game date to disambiguate from other
 * seasons' matchups of the same two teams. Returns null (not an error) if
 * YOUTUBE_API_KEY isn't set, the request fails, or nothing in the results
 * actually looks like a match - callers should treat that as "no highlights
 * available" rather than retrying, to stay well under the 100/day cap.
 */
export async function searchHighlightsVideo(away: string, home: string, tipoffUtc: string): Promise<HighlightsMatch | null> {
  const apiKey = getApiKey();
  if (!apiKey) return null;

  const gameDate = new Date(tipoffUtc);
  const publishedAfter = new Date(Date.UTC(gameDate.getUTCFullYear(), gameDate.getUTCMonth(), gameDate.getUTCDate()));
  const publishedBefore = new Date(publishedAfter.getTime() + 3 * 24 * 60 * 60 * 1000);

  const awayNickname = teamNickname(away);
  const homeNickname = teamNickname(home);
  const query = `${away} ${home} full game highlights`;

  const params = new URLSearchParams({
    key: apiKey,
    // Required by search.list - without it, results omit the snippet
    // object entirely (no title, no publish date), which is why every
    // candidate's title was silently coming through as undefined and
    // failing the match check no matter what the results actually were.
    part: "snippet",
    channelId: NBA_YOUTUBE_CHANNEL_ID,
    q: query,
    type: "video",
    // "date" sorts by newest-upload-first regardless of how well it matches
    // the query - the NBA channel posts many videos a day (recaps, top
    // plays, other games' highlights), so the actual match could easily
    // fall outside the top 5 by recency alone. "relevance" (the default,
    // and what actually matters here) ranks by how well each result matches
    // q, which is what surfaces the right game's video.
    order: "relevance",
    maxResults: "10",
    publishedAfter: publishedAfter.toISOString(),
    publishedBefore: publishedBefore.toISOString(),
  });

  // TEMPORARY: verbose diagnostic logging while tracking down why no games
  // are matching in production. Remove once confirmed working.
  console.log(
    `[YT_SEARCH] query="${query}" channelId=${NBA_YOUTUBE_CHANNEL_ID} ` +
      `window=${publishedAfter.toISOString()}..${publishedBefore.toISOString()}`
  );

  let data: YoutubeSearchResponse;
  try {
    const res = await fetch(`${YOUTUBE_SEARCH_URL}?${params.toString()}`);
    const bodyText = await res.text();
    console.log(`[YT_SEARCH] HTTP ${res.status} for "${query}" - body: ${bodyText.slice(0, 500)}`);

    if (!res.ok) {
      console.error(`YouTube search failed: ${res.status} ${res.statusText}`);
      return null;
    }
    data = JSON.parse(bodyText) as YoutubeSearchResponse;
  } catch (err) {
    console.error("YouTube search request failed", err);
    return null;
  }

  const items = data.items ?? [];
  console.log(`[YT_SEARCH] "${query}" returned ${items.length} item(s): ${items.map((i) => i.snippet?.title).join(" | ")}`);

  for (const item of data.items ?? []) {
    const videoId = item.id?.videoId;
    const title = item.snippet?.title;
    if (!videoId || !title) continue;

    const upperTitle = title.toUpperCase();
    const isMatch =
      upperTitle.includes("FULL GAME HIGHLIGHTS") &&
      upperTitle.includes(awayNickname.toUpperCase()) &&
      upperTitle.includes(homeNickname.toUpperCase());

    console.log(
      `[YT_SEARCH] candidate "${title}" vs nicknames away="${awayNickname}" home="${homeNickname}" -> match=${isMatch}`
    );

    if (isMatch) return { videoId, title };
  }

  console.log(`[YT_SEARCH] no match found for "${query}"`);
  return null;
}
