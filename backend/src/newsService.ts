import { ContentLeague, fetchNews } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, NewsArticleJson, NewsResponseJson } from "./types";

const ARTICLE_LIMIT = 15;

/** Cached once per calendar day per league group - same tradeoff as standings/stats: less fresh than a live feed, but keeps ESPN/Render load flat regardless of user count. */
export async function getNews(leagueGroup: LeagueGroup): Promise<NewsResponseJson> {
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
