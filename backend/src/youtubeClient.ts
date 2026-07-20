// Matches a finished game to its official "FULL GAME HIGHLIGHTS" video on
// the league's own YouTube channel. Channel IDs confirmed via the canonical
// externalId on youtube.com/@NBA, youtube.com/@WNBA, and youtube.com/@MLB
// respectively (not to be confused with unofficial reuploader channels/
// playlists that reuse the same "FULL GAME HIGHLIGHTS" phrase).
const NBA_YOUTUBE_CHANNEL_ID = "UCWJ2lWNubArHWmf3FIHbfcQ";
const WNBA_YOUTUBE_CHANNEL_ID = "UCO9a_ryN_l7DIDS-VIt-zmw";
// MLB's real current title format ("GIANTS vs. MARINERS: Official Full Game
// Highlights (July 19) | 2026 MLB Season" - confirmed directly against the
// channel's live uploads, not assumed) still contains both team nicknames
// and the literal phrase "FULL GAME HIGHLIGHTS" as substrings once
// uppercased ("OFFICIAL FULL GAME HIGHLIGHTS" contains "FULL GAME
// HIGHLIGHTS"), so the existing match predicate below needs no MLB-specific
// branch - only the channel id differs.
const MLB_YOUTUBE_CHANNEL_ID = "UCoLrcjPV5PbUrUyXq5mjc_A";
// Channel id confirmed the same way as every other league above (canonical
// externalId on youtube.com/@NFL) - but unlike NBA/WNBA/MLB, the actual
// "FULL GAME HIGHLIGHTS"-style title format was NOT directly confirmed
// against a real recent upload (the NFL is in its offseason as of this
// build - no fresh games/videos exist to check against, and the channel's
// video-listing page doesn't expose titles without full JS rendering).
// Verify the title format against a real live upload before ever wiring
// checkPendingNflHighlights into highlightsPoller.ts, the same "verify
// before assuming" rule this file's own match predicate exists to enforce
// for every other league.
const NFL_YOUTUBE_CHANNEL_ID = "UCDVYQ4Zhbm3S2dlz7P1GBDg";
// Channel id confirmed the same way as every other league above (canonical
// externalId + <link rel="canonical"> + channelMetadataRenderer.title="NHL"
// on youtube.com/@NHL). Unlike NBA/WNBA/MLB, NHL's real title format is NOT
// "FULL GAME HIGHLIGHTS" - confirmed directly against real live uploads (the
// season just ended, so both a regular-season and a real Stanley Cup Final
// game were checkable, unlike NFL's still-unconfirmed offseason gap):
// regular season titles read "{Away} vs. {Home} | NHL Highlights | {Date}"
// (e.g. "Stars vs. Oilers | NHL Highlights | November 25, 2025", 11:45 long,
// confirmed posted by browseId UCqFMzb-4AUf6WAIbl132QKA / @NHL), postseason
// titles read "{Away} vs. {Home} | NHL Playoff Highlights | Game N | {Date}"
// (e.g. "Hurricanes vs. Golden Knights | NHL Playoff Highlights | Game 6 |
// June 14, 2026" - the real 2026 Stanley Cup Final Game 6, matching ESPN's
// own data for that exact date/matchup). Both contain "HIGHLIGHTS" as a
// substring but never the literal "FULL GAME HIGHLIGHTS" phrase every other
// league's title uses - the shared match predicate below is parameterized
// per-league because of this, not left as a single hardcoded phrase.
const NHL_YOUTUBE_CHANNEL_ID = "UCqFMzb-4AUf6WAIbl132QKA";

export type HighlightsLeague = "nba" | "wnba" | "mlb" | "nfl" | "nhl";

function channelIdFor(league: HighlightsLeague): string {
  if (league === "wnba") return WNBA_YOUTUBE_CHANNEL_ID;
  if (league === "mlb") return MLB_YOUTUBE_CHANNEL_ID;
  if (league === "nfl") return NFL_YOUTUBE_CHANNEL_ID;
  if (league === "nhl") return NHL_YOUTUBE_CHANNEL_ID;
  return NBA_YOUTUBE_CHANNEL_ID;
}

// NHL's real titles never contain the literal "FULL GAME HIGHLIGHTS" phrase
// (see NHL_YOUTUBE_CHANNEL_ID's own comment) - just "HIGHLIGHTS" alongside
// either "NHL Highlights" or "NHL Playoff Highlights". Every other league's
// confirmed real titles do contain the full "FULL GAME HIGHLIGHTS" phrase,
// so requiring only the broader "HIGHLIGHTS" substring for them too would
// risk matching an unrelated video (a "Top Plays" or "Best Highlights"
// clip-show upload) that happens to share the team-name substrings - keeping
// the stricter phrase for those leagues is deliberate, not an oversight.
function requiredTitlePhraseFor(league: HighlightsLeague): string {
  return league === "nhl" ? "HIGHLIGHTS" : "FULL GAME HIGHLIGHTS";
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

// Real full-game-highlights reels from the NBA/WNBA channels run well under
// this (observed 9-15 min in the regular season, but Summer League reels
// have been confirmed as long as 15:38 - higher-scoring, sloppier games run
// longer highlight packages) - a plain broadcast replay, condensed classic,
// or unrelated long-form video matching the team names on text alone would
// blow past this, so it still catches false positives text matching alone
// can't, without rejecting genuine highlights videos that run a bit long.
// Reused as-is for MLB (not independently verified against real MLB reel
// durations - a baseball highlights package is typically similarly
// condensed regardless of the sport's longer raw game length, but this cap
// is worth revisiting against real data once MLB search is actually wired
// in and producing matches).
const MAX_HIGHLIGHTS_SECONDS = 20 * 60;

interface YoutubeSearchItem {
  id?: { videoId?: string };
  snippet?: { title?: string; publishedAt?: string };
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
  // The video's own upload timestamp (search.list's snippet.publishedAt) -
  // real, independent ground truth for upload lag, unlike yt_found_at
  // (which only ever reflects when *our own* check schedule happened to
  // look, not when the video actually went up). See gameStore.ts's
  // getLagPercentiles for why this matters.
  publishedAt: string | null;
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
  // NHL's own confirmed real titles say "NHL Highlights"/"NHL Playoff
  // Highlights", never "full game highlights" - querying with that literal
  // phrase for NHL would still likely surface the same video (YouTube's
  // search ranking isn't an exact-substring match), but querying with what
  // the title actually says is the same "don't guess, match what's real"
  // principle every other league's query construction already follows here.
  const query =
    league === "nhl" ? `${awayNickname} ${homeNickname} NHL highlights` : `${awayNickname} ${homeNickname} full game highlights`;

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
      upperTitle.includes(requiredTitlePhraseFor(league)) &&
      upperTitle.includes(awayNickname.toUpperCase()) &&
      upperTitle.includes(homeNickname.toUpperCase());

    if (isMatch) textMatches.push({ videoId, title, publishedAt: item.snippet?.publishedAt ?? null });
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
