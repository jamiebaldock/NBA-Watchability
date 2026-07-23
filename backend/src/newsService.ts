import { ContentLeague, fetchNews, fetchNewsForSport } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, NewsArticleJson, NewsResponseJson, SPORT_FOR_LEAGUE_GROUP } from "./types";

const ARTICLE_LIMIT = 15;

/** Cached once per calendar day per league group - same tradeoff as standings/stats: less fresh than a live feed, but keeps ESPN/Render load flat regardless of user count. */
export async function getNews(leagueGroup: LeagueGroup): Promise<NewsResponseJson> {
  // Unlike standings/stats (whose ESPN endpoints tolerate an unrecognized
  // league slug and just come back empty) and unlike leaders (whose
  // fetchLeaders already catches), news' sport-namespaced URL 404s for a
  // leagueGroup with no branch below and getJson throws - short-circuit
  // before ever building that request rather than letting an unhandled
  // rejection surface as a 500.
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];
  if (sport !== "basketball" && sport !== "baseball" && sport !== "hockey" && sport !== "football") {
    return { articles: [] };
  }

  const now = new Date();
  const dateKey = todayKey(now);

  const cached = loadLeagueCache<NewsResponseJson>("news", leagueGroup, dateKey);
  if (cached) return cached;

  const articles =
    sport === "baseball"
      ? await fetchNewsForSport("baseball", "mlb", ARTICLE_LIMIT)
      : sport === "hockey"
        ? await fetchNewsForSport("hockey", "nhl", ARTICLE_LIMIT)
        : sport === "football"
          ? await fetchNewsForSport("football", "nfl", ARTICLE_LIMIT)
          : await fetchNews(leagueGroup as ContentLeague, ARTICLE_LIMIT);
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
