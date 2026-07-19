import { ContentLeague, fetchNews } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, NewsArticleJson, NewsResponseJson, SPORT_FOR_LEAGUE_GROUP } from "./types";

const ARTICLE_LIMIT = 15;

/** Cached once per calendar day per league group - same tradeoff as standings/stats: less fresh than a live feed, but keeps ESPN/Render load flat regardless of user count. */
export async function getNews(leagueGroup: LeagueGroup): Promise<NewsResponseJson> {
  // Unlike standings/stats (whose ESPN endpoints tolerate an unrecognized
  // league slug and just come back empty) and unlike leaders (whose
  // fetchLeaders already catches), news' basketball-namespaced URL 404s for
  // a non-basketball leagueGroup (e.g. MLB) and getJson throws -
  // short-circuit before ever building that request rather than letting an
  // unhandled rejection surface as a 500. No News tab exists for non-
  // basketball leagues yet (Games tab first, same as History), so an empty
  // result is the correct answer here anyway.
  if (SPORT_FOR_LEAGUE_GROUP[leagueGroup] !== "basketball") {
    return { articles: [] };
  }

  const league = leagueGroup as ContentLeague;
  const now = new Date();
  const dateKey = todayKey(now);

  const cached = loadLeagueCache<NewsResponseJson>("news", leagueGroup, dateKey);
  if (cached) return cached;

  const articles = await fetchNews(league, ARTICLE_LIMIT);
  const response: NewsResponseJson = {
    articles: articles.map(
      (a): NewsArticleJson => ({
        id: a.id,
        headline: a.headline,
        description: a.description,
        image: a.images?.[0]?.url,
        link: a.links?.web?.href,
        published: a.published,
      })
    ),
  };

  saveLeagueCache("news", leagueGroup, dateKey, response);
  return response;
}
