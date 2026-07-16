// Matches a finished game to its official "FULL GAME HIGHLIGHTS" video on
// the league's own YouTube channel. Channel IDs confirmed via the canonical
// link on youtube.com/@NBA and youtube.com/@WNBA respectively (not to be
// confused with unofficial reuploader channels/playlists that reuse the
// same "FULL GAME HIGHLIGHTS" phrase).
const NBA_YOUTUBE_CHANNEL_ID = "UCWJ2lWNubArHWmf3FIHbfcQ";
const WNBA_YOUTUBE_CHANNEL_ID = "UCO9a_ryN_l7DIDS-VIt-zmw";

export type HighlightsLeague = "nba" | "wnba";

function channelIdFor(league: HighlightsLeague): string {
  return league === "wnba" ? WNBA_YOUTUBE_CHANNEL_ID : NBA_YOUTUBE_CHANNEL_ID;
}

// search.list has its own dedicated daily quota bucket (separate from the
// general 10,000-unit pool), hard-capped at 100 calls/day as of Google's
// June 2026 quota change - this is only safe because gamesService.ts caches
// a match forever and gates retries of a miss on a cooldown (cache.ts's
// ytCheckedAt), never re-searching on every single request.
const YOUTUBE_SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";

// videos.list draws from the general 10,000-unit pool at 1 unit/call, not
// the scarce 100/day search bucket - safe to spend on every candidate
// batch without threatening the search quota.
const YOUTUBE_VIDEOS_URL = "https://www.googleapis.com/youtube/v3/videos";

// Real full-game-highlights reels from both channels run well under this
// (observed 9-15 min in the regular season, but Summer League reels have
// been confirmed as long as 15:38 - higher-scoring, sloppier games run
// longer highlight packages) - a plain broadcast replay, condensed classic,
// or unrelated long-form video matching the team names on text alone would
// blow past this, so it still catches false positives text matching alone
// can't, without rejecting genuine highlights videos that run a bit long.
const MAX_HIGHLIGHTS_SECONDS = 20 * 60;

interface YoutubeSearchItem {
  id?: { videoId?: string };
  snippet?: { title?: string };
}

interface YoutubeSearchResponse {
  items?: YoutubeSearchItem[];
}

interface YoutubeVideoItem {
  id?: string;
  contentDetails?: { duration?: string };
}

interface YoutubeVideosResponse {
  items?: YoutubeVideoItem[];
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
 * Both channels' official titles use just the team nickname ("HORNETS at
 * JAZZ", "...Golden State Valkyries vs. Connecticut Sun..."), not the
 * "city + nickname" display name alone - the last word of the display name
 * matches this for every team except "Trail Blazers", where "Blazers"
 * alone is still a safe substring match against either spelling.
 */
function teamNickname(displayName: string): string {
  return displayName.trim().split(/\s+/).pop() ?? displayName;
}

/** Parses ISO 8601 durations (e.g. "PT9M37S", "PT1H13M") into total seconds. */
function parseIsoDurationSeconds(duration: string): number | null {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(duration);
  if (!match) return null;
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  const seconds = Number(match[3] ?? 0);
  return hours * 3600 + minutes * 60 + seconds;
}

// Temporary diagnostic (devServer.ts's /admin/lag-samples) - fetches each
// matched video's own snippet.publishedAt, the one measure of true YouTube
// upload time that's independent of when our poller happened to look.
// Remove once the upload-lag-measurement investigation is resolved.
export async function fetchPublishedAtTimes(videoIds: string[]): Promise<Map<string, string>> {
  const published = new Map<string, string>();
  if (videoIds.length === 0) return published;
  const apiKey = getApiKey();
  if (!apiKey) return published;

  const params = new URLSearchParams({
    key: apiKey,
    part: "snippet",
    id: videoIds.join(","),
  });

  try {
    const res = await fetch(`${YOUTUBE_VIDEOS_URL}?${params.toString()}`);
    if (!res.ok) return published;
    const data = (await res.json()) as { items?: { id?: string; snippet?: { publishedAt?: string } }[] };
    for (const item of data.items ?? []) {
      if (item.id && item.snippet?.publishedAt) published.set(item.id, item.snippet.publishedAt);
    }
  } catch (err) {
    console.error("YouTube videos.list (publishedAt check) failed", err);
  }

  return published;
}

/** Fetches durations for a batch of video IDs in a single call (comma-joined, 1 quota unit total regardless of count). */
async function fetchDurationsSeconds(apiKey: string, videoIds: string[]): Promise<Map<string, number>> {
  const durations = new Map<string, number>();
  if (videoIds.length === 0) return durations;

  const params = new URLSearchParams({
    key: apiKey,
    part: "contentDetails",
    id: videoIds.join(","),
  });

  try {
    const res = await fetch(`${YOUTUBE_VIDEOS_URL}?${params.toString()}`);
    if (!res.ok) return durations;
    const data = (await res.json()) as YoutubeVideosResponse;
    for (const item of data.items ?? []) {
      const duration = item.contentDetails?.duration;
      const seconds = duration ? parseIsoDurationSeconds(duration) : null;
      if (item.id && seconds !== null) durations.set(item.id, seconds);
    }
  } catch (err) {
    console.error("YouTube videos.list (duration check) failed", err);
  }

  return durations;
}

/**
 * Searches [league]'s official channel for this game's full-game-highlights
 * video, scoped to a tight window around the game date to disambiguate from
 * other seasons' matchups of the same two teams. Candidates must match both
 * team nicknames and the "full game highlights" phrase in the title, AND
 * run under MAX_HIGHLIGHTS_SECONDS (a real highlights reel's length - filters
 * out unrelated long-form videos that happen to match on text alone). Returns
 * null (not an error) if YOUTUBE_API_KEY isn't set, the request fails, or
 * nothing qualifies - callers should treat that as "no highlights
 * available" rather than retrying, to stay well under the 100/day cap.
 */
export async function searchHighlightsVideo(
  league: HighlightsLeague,
  away: string,
  home: string,
  tipoffUtc: string
): Promise<HighlightsMatch | null> {
  const apiKey = getApiKey();
  if (!apiKey) return null;

  const gameDate = new Date(tipoffUtc);
  const publishedAfter = new Date(Date.UTC(gameDate.getUTCFullYear(), gameDate.getUTCMonth(), gameDate.getUTCDate()));
  const publishedBefore = new Date(publishedAfter.getTime() + 3 * 24 * 60 * 60 * 1000);

  const awayNickname = teamNickname(away);
  const homeNickname = teamNickname(home);
  // Both channels' official titles use nickname-only ("CAVALIERS vs PACERS
  // | ... | FULL GAME HIGHLIGHTS"), never the "City Nickname" display name -
  // querying with the full display name ("Cleveland Cavaliers Indiana
  // Pacers...") was diluting relevance ranking against the channel's dense
  // day-to-day upload volume (recaps, top plays, other games) and could push
  // the actual match outside maxResults, especially during Summer League
  // where the channel posts far more same-day videos than a regular-season
  // day. Querying with what the title actually contains ranks it correctly.
  const query = `${awayNickname} ${homeNickname} full game highlights`;

  const params = new URLSearchParams({
    key: apiKey,
    // Required by search.list - without it, results omit the snippet
    // object entirely (no title, no publish date), which is why every
    // candidate's title was silently coming through as undefined and
    // failing the match check no matter what the results actually were.
    part: "snippet",
    channelId: channelIdFor(league),
    q: query,
    type: "video",
    // "date" sorts by newest-upload-first regardless of how well it matches
    // the query - the channel posts many videos a day (recaps, top plays,
    // other games' highlights), so the actual match could easily fall
    // outside the top 5 by recency alone. "relevance" (the default, and
    // what actually matters here) ranks by how well each result matches q,
    // which is what surfaces the right game's video.
    order: "relevance",
    // maxResults doesn't affect search.list's quota cost (still 100 units
    // regardless of count) - a wider net costs nothing and gives a real
    // margin against the denser Summer League candidate pool.
    maxResults: "20",
    publishedAfter: publishedAfter.toISOString(),
    publishedBefore: publishedBefore.toISOString(),
  });

  let data: YoutubeSearchResponse;
  try {
    const res = await fetch(`${YOUTUBE_SEARCH_URL}?${params.toString()}`);
    if (!res.ok) {
      console.error(`YouTube search failed: ${res.status} ${res.statusText}`);
      return null;
    }
    data = (await res.json()) as YoutubeSearchResponse;
  } catch (err) {
    console.error("YouTube search request failed", err);
    return null;
  }

  const textMatches: HighlightsMatch[] = [];
  for (const item of data.items ?? []) {
    const videoId = item.id?.videoId;
    const title = item.snippet?.title;
    if (!videoId || !title) continue;

    const upperTitle = title.toUpperCase();
    const isMatch =
      upperTitle.includes("FULL GAME HIGHLIGHTS") &&
      upperTitle.includes(awayNickname.toUpperCase()) &&
      upperTitle.includes(homeNickname.toUpperCase());

    if (isMatch) textMatches.push({ videoId, title });
  }

  if (textMatches.length === 0) return null;

  const durations = await fetchDurationsSeconds(
    apiKey,
    textMatches.map((m) => m.videoId)
  );

  for (const candidate of textMatches) {
    const seconds = durations.get(candidate.videoId);
    if (seconds !== undefined && seconds <= MAX_HIGHLIGHTS_SECONDS) return candidate;
  }

  return null;
}
