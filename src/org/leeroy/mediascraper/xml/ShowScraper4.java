// Copyright 2020 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package org.leeroy.mediascraper.xml;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.SparseArray;

import org.leeroy.medialib.R;
import org.leeroy.mediaprovider.video.ScraperStore;
import org.leeroy.mediascraper.EpisodeTags;
import org.leeroy.mediascraper.ScrapeDetailResult;
import org.leeroy.mediascraper.ScrapeSearchResult;
import org.leeroy.mediascraper.ScrapeStatus;
import org.leeroy.mediascraper.Scraper;
import org.leeroy.mediascraper.ScraperCache;
import org.leeroy.mediascraper.ScraperImage;
import org.leeroy.mediascraper.SearchResult;
import org.leeroy.mediascraper.ShowTags;
import org.leeroy.mediascraper.ShowUtils;
import org.leeroy.mediascraper.preprocess.SearchInfo;
import org.leeroy.mediascraper.preprocess.TvShowSearchInfo;
import org.leeroy.mediascraper.themoviedb3.MyTmdb;
import org.leeroy.mediascraper.themoviedb3.SearchShow;
import org.leeroy.mediascraper.themoviedb3.SearchShowResult;
import org.leeroy.mediascraper.themoviedb3.ShowIdEpisodeSearch;
import org.leeroy.mediascraper.themoviedb3.ShowIdEpisodeSearchResult;
import org.leeroy.mediascraper.themoviedb3.ShowIdEpisodes;
import org.leeroy.mediascraper.themoviedb3.ShowIdImagesParser;
import org.leeroy.mediascraper.themoviedb3.ShowIdImagesResult;
import org.leeroy.mediascraper.themoviedb3.ShowIdParser;
import org.leeroy.mediascraper.themoviedb3.ShowIdSeasonSearch;
import org.leeroy.mediascraper.themoviedb3.ShowIdSeasonSearchResult;
import org.leeroy.mediascraper.themoviedb3.ShowIdTvSearch;
import org.leeroy.mediascraper.themoviedb3.ShowIdTvSearchResult;
import com.uwetrottmann.tmdb2.entities.TvEpisode;
import com.uwetrottmann.tmdb2.entities.TvSeason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Cache;

import static org.leeroy.mediascraper.TagsFactory.buildShowTagsOnlineId;

import androidx.preference.PreferenceManager;

public class ShowScraper4 extends BaseScraper2 {
    private static final String PREFERENCE_NAME = "themoviedb.org";

    private static final Logger log = LoggerFactory.getLogger(ShowScraper4.class);

    // Wrapper class to cache show metadata including number of seasons
    private static class ShowMetadata {
        ShowTags showTags;
        int numberOfSeasons;

        ShowMetadata(ShowTags showTags, int numberOfSeasons) {
            this.showTags = showTags;
            this.numberOfSeasons = numberOfSeasons;
        }
    }

    // Benchmarks tells that with tv shows sorted in folders, size of 100 or 10 or even provides the same cacheHits on fake collection of 30k episodes, 250 shows
    private final static LruCache<String, Map<String, EpisodeTags>> sEpisodeCache = new LruCache<>(100);

    // Cache season poster mappings to avoid repeated DB queries when scraping multiple episodes from same show
    private final static LruCache<String, SparseArray<ScraperImage>> sSeasonPosterCache = new LruCache<>(50);

    // Cache show metadata (ShowTags + number_of_seasons) to avoid redundant API calls when scraping multiple episodes/seasons from same show
    private final static LruCache<String, ShowMetadata> sShowMetadataCache = new LruCache<>(200);

    // Add caching for OkHttpClient so that queries for episodes from a same tvshow will get a boost in resolution
    static Cache cache;

    static MyTmdb tmdb = null;
    static String apiKey = null;

    public ShowScraper4(Context context) {
        super(context);
        // ensure cache is initialized
        synchronized (ShowScraper4.class) {
            cache = ScraperCache.getCache(context);
            apiKey = context.getString(R.string.tmdb_api_key);
        }
    }

    public static void debugLruCache(LruCache<String, Map<String, EpisodeTags>> lruCache) {
        log.debug("debugLruCache: size={}", lruCache.size());
        log.debug("debugLruCache: putCount={}", lruCache.putCount());
        log.debug("debugLruCache: hitCount={}", lruCache.hitCount());
        log.debug("debugLruCache: missCount={}", lruCache.missCount());
        log.debug("debugLruCache: evictionCount={}", lruCache.evictionCount());
    }

    public static void reauth() {
        tmdb = new MyTmdb(apiKey, cache);
    }

    @Override
    public ScrapeSearchResult getMatches2(SearchInfo info, int maxItems) {
        // maxItems = -1 means all
        // check input
        // can return a tvshow with less seasons that the one required in info
        if (info == null || !(info instanceof TvShowSearchInfo)) {
            log.error("getMatches2: bad search info: {}", info == null ? "null" : "movie in show scraper");
            log.debug("getMatches2: ScrapeSearchResult ScrapeStatus.ERROR");
            return new ScrapeSearchResult(null, false, ScrapeStatus.ERROR, null);
        }
        TvShowSearchInfo searchInfo = (TvShowSearchInfo) info;
        // get configured language
        String language = Scraper.getLanguage(mContext);
        log.debug("getMatches2: tvshow search:{} s:{} e:{}, maxItems={}, language={}",
                searchInfo.getShowName(), searchInfo.getSeason(), searchInfo.getEpisode(), maxItems, language);
        if (tmdb == null) reauth();
        SearchShowResult searchResult = SearchShow.search(searchInfo, language, maxItems, adultScrape,this, tmdb);
        if (searchResult.result.size() > 0) log.debug("getMatches2: match found {} id {}", searchResult.result.get(0).getTitle(), searchResult.result.get(0).getId());
        return new ScrapeSearchResult(searchResult.result, false, searchResult.status, searchResult.reason);
    }

    @Override
    protected ScrapeDetailResult getDetailsInternal(SearchResult result, Bundle options) {
        // result is the global tvShow

        boolean doRebuildShowTag = false;
        // never reuse old show info since there could be new episodes/seasons
        final boolean useOldShow = false;
        // ITEM_REQUEST_BASIC_SHOW = true means show (without episodes) is to be scraped manually (ManualShowScrappingSearchFragment)
        //  --> no need to get full season or else we have already all info in getMatch2
        // ITEM_REQUEST_BASIC_VIDEO = true means single episode is to be scraped manually (ManualVideoScrappingSearchFragment/VideoInfoScraperSearchFragment)
        //  --> no need to get full season but in this case we have already ITEM_REQUEST_EPISODE set (TBC)

        boolean basicShow = options != null && options.containsKey(Scraper.ITEM_REQUEST_BASIC_SHOW);
        boolean basicEpisode = options != null && options.containsKey(Scraper.ITEM_REQUEST_BASIC_VIDEO);
        boolean getAllEpisodes = options != null && options.containsKey(Scraper.ITEM_REQUEST_ALL_EPISODES);
        int season = -1;
        int episode = -1;
        if (options != null) {
            season = options.getInt(Scraper.ITEM_REQUEST_SEASON, -1);
            episode = options.getInt(Scraper.ITEM_REQUEST_EPISODE, -1);
        } else
            log.debug("getDetailsInternal: options is null");

        if (episode != -1) log.error("getDetailsInternal: episode should NEVER be -1 since cannot get on single episode season poster!!!");

        String resultLanguage = result.getLanguage();
        if (TextUtils.isEmpty(resultLanguage))
            resultLanguage = "en";
        int showId = result.getId();
        String key = (getAllEpisodes ? "all" : (season != -1 ? "s" + season : "") + (episode != -1 ? "e" + episode : ""));
        String showKey = showId + "|" + key + "|" + resultLanguage;

        // Parse season and episode numbers once to avoid repeated Integer.parseInt() calls
        int requestedSeason = Integer.parseInt(result.getExtra().getString(ShowUtils.SEASON, "0"));
        int requestedEpisode = Integer.parseInt(result.getExtra().getString(ShowUtils.EPNUM, "0"));

        log.debug("getDetailsInternal: {}({}) {} in {} (basicShow={}/basicEpisode={})",
                result.getTitle(), showId, key, resultLanguage, basicShow, basicEpisode);

        Map<String, EpisodeTags> allEpisodes = null;
        ShowTags showTags = null;
        ShowIdImagesResult searchImages = null;

        log.debug("getDetailsInternal: probing cache for showKey {}", showKey);
        allEpisodes = sEpisodeCache.get(showKey);
        if (log.isTraceEnabled()) debugLruCache(sEpisodeCache);

        if (allEpisodes == null) {
            log.debug("getDetailsInternal: allEpisodes is null, need to get show");

            // if we get allEpisodes it means we also have global show info and there is no need to redo it
            if (tmdb == null) reauth();
            showTags = new ShowTags(); // to get the global show info
            allEpisodes = new HashMap<>(); // to get all episodes info

            int number_of_seasons = -1;

            // need to parse that show
            // start with global show information before retrieving all episodes
            // check if show metadata is already in database to avoid redundant API calls
            // however, for getAllEpisodes we still need fresh data in case new episodes/seasons were added
            Boolean isShowKnown = useOldShow && !getAllEpisodes && isShowAlreadyKnown(showId, mContext);
            log.debug("getDetailsInternal: show known {}", isShowKnown);

            if (! isShowKnown || getAllEpisodes) {
                String lang = resultLanguage;
                // for getAllEpisodes we need to get the number of seasons thus get it
                log.debug("getDetailsInternal: show {} not known or getAllEpisodes {}", showId, getAllEpisodes);

                // Check metadata cache first
                String metadataCacheKey = showId + "|" + resultLanguage;
                ShowMetadata cachedMetadata = sShowMetadataCache.get(metadataCacheKey);
                ShowIdTvSearchResult showIdTvSearchResult = null;

                if (cachedMetadata == null) {
                    log.debug("getDetailsInternal: show metadata cache miss, fetching from API");
                    // query first tmdb
                    showIdTvSearchResult = ShowIdTvSearch.getTvShowResponse(showId, resultLanguage, adultScrape, tmdb);
                    // parse result to get global show basic info
                    if (showIdTvSearchResult.status != ScrapeStatus.OKAY)
                        return new ScrapeDetailResult(new ShowTags(), true, null, showIdTvSearchResult.status, showIdTvSearchResult.reason);
                    else showTags = ShowIdParser.getResult(showIdTvSearchResult.tvShow, result.getYear(), mContext);
                    log.debug("getDetailsInternal: downloaded showTags {} {}", showTags.getOnlineId(), showTags.getTitle());

                    // if there is no title or description research in en
                    if (showTags.getPlot() == null || showTags.getTitle() == null || showTags.getPlot().length() == 0 || showTags.getTitle().length() == 0) {
                        showIdTvSearchResult = ShowIdTvSearch.getTvShowResponse(showId, "en", adultScrape, tmdb);
                        if (showIdTvSearchResult.status != ScrapeStatus.OKAY)
                            return new ScrapeDetailResult(showTags, true, null, showIdTvSearchResult.status, showIdTvSearchResult.reason);
                        else showTags = ShowIdParser.getResult(showIdTvSearchResult.tvShow, result.getYear(), mContext);
                    }

                    // now we have the number of seasons if we need getAllEpisodes
                    number_of_seasons = showIdTvSearchResult.tvShow.number_of_seasons;
                    if (number_of_seasons < season) log.warn("getDetailsInternal: season ({}) > number_of_seasons ({})", season, number_of_seasons);
                    // no need to do this if show known
                    if (!isShowKnown) {
                        log.debug("getDetailsInternal: get all images for show {}", showId);

                        // get show posters and backdrops
                        searchImages = ShowIdImagesParser.getResult(showTags.getTitle(), showIdTvSearchResult.tvShow, lang, mContext);
                        if (!searchImages.backdrops.isEmpty())
                            showTags.setBackdrops(searchImages.backdrops);
                        else log.debug("getDetailsInternal: backdrops empty!");
                        // needs to be done after setBackdrops not to be erased
                        if (result.getBackdropPath() != null)  showTags.addDefaultBackdropTMDB(mContext, result.getBackdropPath());
                        if (!searchImages.posters.isEmpty())
                            showTags.setPosters(searchImages.posters);
                        else log.debug("getDetailsInternal: posters empty!");
                        // needs to be done after setPosters not to be erased
                        if (result.getPosterPath() != null) showTags.addDefaultPosterTMDB(mContext, result.getPosterPath());

                        // only downloads main backdrop/poster and not the entire collection (x8 in size)
                        showTags.downloadPoster(mContext);
                        showTags.downloadBackdrop(mContext);
                        //showTags.downloadPosters(mContext);
                        //showTags.downloadBackdrops(mContext);
                    } else {
                        doRebuildShowTag = true;
                    }

                    // Cache the show metadata (including number_of_seasons) for future scrapes
                    sShowMetadataCache.put(metadataCacheKey, new ShowMetadata(showTags, number_of_seasons));
                    log.debug("getDetailsInternal: cached show metadata for show {} with {} seasons", showId, number_of_seasons);
                } else {
                    log.debug("getDetailsInternal: show metadata cache hit for show {}", showId);
                    // Extract cached data
                    showTags = cachedMetadata.showTags;
                    number_of_seasons = cachedMetadata.numberOfSeasons;
                    log.debug("getDetailsInternal: using cached number_of_seasons={}", number_of_seasons);
                }
            } else {
                doRebuildShowTag = true;
            }

            if (doRebuildShowTag == true) {
                log.debug("getDetailsInternal: show {} is known: rebuild from tag", showId);
                // showTags exits we get it from db
                showTags = buildShowTagsOnlineId(mContext, showId);
                if (showTags == null)
                    log.warn("getDetailsInternal: show {} tag is null but known!", showId);
                else log.debug("getDetailsInternal: show {} {} in {} already known: {}, plot: {}",
                        showId, key, resultLanguage, showTags.getTitle(), showTags.getPlot());
            }

            // retreive now the desired episodes
            List<TvEpisode> tvEpisodes = new ArrayList<>();
            Map<Integer, TvSeason> tvSeasons = new HashMap<Integer, TvSeason>();

            if (getAllEpisodes) {
                // get all episodes: loop over seasons and concatenate
                for (int s = 1; s <= number_of_seasons; s++) {
                    log.debug("getDetailsInternal: get episodes for show " + showId + " s" + s);
                    ShowIdSeasonSearchResult showIdSeason = ShowIdSeasonSearch.getSeasonShowResponse(showId, s, resultLanguage, adultScrape, tmdb);
                    if (showIdSeason.status == ScrapeStatus.OKAY) {
                        tvEpisodes.addAll(showIdSeason.tvSeason.episodes);
                        if (! tvSeasons.containsKey(showIdSeason.tvSeason.season_number))
                            tvSeasons.put(showIdSeason.tvSeason.season_number, showIdSeason.tvSeason);
                    } else {
                        log.warn("getDetailsInternal: scrapeStatus for s" + s + " is NOK!");
                        return new ScrapeDetailResult(new EpisodeTags(), true, null, showIdSeason.status, showIdSeason.reason);
                    }
                }
            } else {
                if (episode != -1) {
                    // get a single episode: should never get there since it means that we cannot infer poster/backdrop from single episode (need season)
                    log.debug("getDetailsInternal: get single episode for show {} s{}e{}", showId, season, episode);
                    ShowIdEpisodeSearchResult showIdEpisode = ShowIdEpisodeSearch.getEpisodeShowResponse(showId, season, episode, resultLanguage, adultScrape, tmdb);
                    if (showIdEpisode.status == ScrapeStatus.OKAY)
                        tvEpisodes.add(showIdEpisode.tvEpisode);
                    else {
                        log.warn("getDetailsInternal: scrapeStatus for s{}e{} is NOK!", season, episode);
                        // save showtag even if episodetag is empty
                        EpisodeTags episodeTag = new EpisodeTags();
                        episodeTag.setShowTags(showTags);
                        // even if this is nok record season and episode not to end up with s00e00
                        episodeTag.setSeason(requestedSeason);
                        episodeTag.setEpisode(requestedEpisode);
                        return new ScrapeDetailResult(episodeTag, true, null, showIdEpisode.status, showIdEpisode.reason);
                    }
                } else {
                    // by default we get the whole season on which the show has been identified
                    if (season == -1) {
                        log.error("getDetailsInternal: season cannot be -1!!!");
                        // save showtag even if episodetag is empty
                        EpisodeTags episodeTag = new EpisodeTags();
                        episodeTag.setShowTags(showTags);
                        return new ScrapeDetailResult(episodeTag, true, null, ScrapeStatus.ERROR_PARSER, null);
                    }
                    log.debug("getDetailsInternal: get full season for show {} s{}", showId, season);
                    ShowIdSeasonSearchResult showIdSeason = ShowIdSeasonSearch.getSeasonShowResponse(showId, season, resultLanguage, adultScrape, tmdb);
                    if (showIdSeason.status == ScrapeStatus.OKAY) {
                        tvEpisodes.addAll(showIdSeason.tvSeason.episodes);
                        tvSeasons.putIfAbsent(showIdSeason.tvSeason.season_number, showIdSeason.tvSeason);
                    } else {
                        // save showtag even if episodetag is empty
                        EpisodeTags episodeTag = new EpisodeTags();
                        episodeTag.setShowTags(showTags);
                        // even if this is nok record season and episode not to end up with s00e00
                        episodeTag.setSeason(requestedSeason);
                        episodeTag.setEpisode(requestedEpisode);
                        log.warn("getDetailsInternal: scrapeStatus for season {} is NOK!", season);
                        return new ScrapeDetailResult(episodeTag, true, null, showIdSeason.status, showIdSeason.reason);
                    }
                }
            }

            // get now all episodes in tvEpisodes
            Map<String, EpisodeTags> searchEpisodes = ShowIdEpisodes.getEpisodes(showId, tvEpisodes, tvSeasons, showTags, resultLanguage, adultScrape, tmdb, mContext);
            if (!searchEpisodes.isEmpty()) {
                allEpisodes = searchEpisodes;
                // put that result in cache.
                log.debug("getDetailsInternal: sEpisodeCache put allEpisodes with key {}", showKey);
                sEpisodeCache.put(showKey, allEpisodes);
            }

            // if we have episodes and posters map them to each other
            if (!allEpisodes.isEmpty()) {
                String showPosterKey = showId + "|" + resultLanguage;
                SparseArray<ScraperImage> seasonPosters = sSeasonPosterCache.get(showPosterKey);
                if (seasonPosters == null) {
                    List<ScraperImage> postersFromDb = showTags.getAllPostersInDb(mContext);
                    if (!postersFromDb.isEmpty()) {
                        seasonPosters = buildSeasonPosterMap(postersFromDb, resultLanguage);
                        sSeasonPosterCache.put(showPosterKey, seasonPosters);
                        log.debug("getDetailsInternal: cached season posters for show {}", showId);
                    }
                } else {
                    log.debug("getDetailsInternal: using cached season posters for show {}", showId);
                }
                if (seasonPosters != null)
                    mapPostersToEpisodes(allEpisodes, seasonPosters);
            }
        } else {
            log.debug("getDetailsInternal: cache boost for showId (all episodes)");
            // no need to parse, we have a cached result
            // get the showTags out of one random element, they all contain the same
            Iterator<EpisodeTags> iter = allEpisodes.values().iterator();
            if (iter.hasNext()) showTags = iter.next().getShowTags();
        }
        if (showTags == null) { // if there is no info about the show there is nothing we can do
            log.debug("getDetailsInternal: ScrapeStatus.ERROR_PARSER");
            return new ScrapeDetailResult(null, false, null, ScrapeStatus.ERROR_PARSER, null);
        }
        EpisodeTags returnValue = buildTag(allEpisodes, requestedEpisode, requestedSeason, showTags);
        log.debug("getDetailsInternal : ScrapeStatus.OKAY {} {} {}", returnValue.getShowTitle(), returnValue.getShowId(), returnValue.getTitle());
        Bundle extraOut = buildBundle(allEpisodes, options);
        return new ScrapeDetailResult(returnValue, false, extraOut, ScrapeStatus.OKAY, null);
    }

    private static SparseArray<ScraperImage> buildSeasonPosterMap(List<ScraperImage> posters, String language) {
        // array to map season -> image
        SparseArray<ScraperImage> seasonPosters = new SparseArray<ScraperImage>();
        for (ScraperImage image : posters) {
            int season = image.getSeason();
            // season -1 is invalid, set the first only
            if (season >= 0) {
                if (seasonPosters.get(season) == null)
                    seasonPosters.put(season, image);
                else if (language.equals(image.getLanguage())) { //reset if right language
                    seasonPosters.put(season, image);
                }
            }
        }
        return seasonPosters;
    }

    private static void mapPostersToEpisodes(Map<String, EpisodeTags> allEpisodes, SparseArray<ScraperImage> seasonPosters) {
        // try to find a season poster for each episode
        for (EpisodeTags episode : allEpisodes.values()) {
            int season = episode.getSeason();
            ScraperImage image = seasonPosters.get(season);
            if (image != null) {
                episode.setPosters(image.asList());
                // not downloading that here since we don't want all posters for
                // all episodes.
            }
        }
    }

    private EpisodeTags buildTag(Map<String, EpisodeTags> allEpisodes, int epnum, int season, ShowTags showTags) {
        log.debug("buildTag allEpisodes.size={} epnum={}, season={}, showId={}", allEpisodes.size(), epnum, season, showTags.getId());
        EpisodeTags episodeTag = null;
        if (!allEpisodes.isEmpty()) {
            String key = season + "|" + epnum;
            log.debug("buildTag: allEpisodes not empty trying to find {}", key);
            episodeTag = allEpisodes.get(key);
        }
        if (episodeTag == null) {
            log.debug("buildTag: shoot episode not in allEpisodes");
            episodeTag = new EpisodeTags();
            // assume episode / season of request
            episodeTag.setSeason(season);
            episodeTag.setEpisode(epnum);
            episodeTag.setShowTags(showTags);
            // also check if there is a poster
            List<ScraperImage> posters = showTags.getPosters();
            if (posters != null) {
                log.debug("buildTag: posters not null");
                for (ScraperImage image : posters) {
                    if (image.getSeason() == season) {
                        log.debug("buildTag: {} season poster s{} {}", showTags.getTitle(), season, image.getLargeUrl());
                        episodeTag.setPosters(image.asList());
                        episodeTag.downloadPoster(mContext);
                        break;
                    }
                }
            }
        } else {
            log.debug("buildTag: episodeTag not null");
            if (episodeTag.getPosters() == null) {
                log.warn("buildTag: {} has null posters!", episodeTag.getTitle());
            } else if (episodeTag.getPosters().isEmpty()) {
                log.warn("buildTag: {} has empty posters!", episodeTag.getTitle());
            }
            if (episodeTag.getDefaultPoster() == null) {
                log.warn("buildTag: {} has no defaultPoster! Should add default show one.", episodeTag.getTitle());
            }
            if (episodeTag.getShowTags() == null) {
                log.warn("buildTag: {} has empty showTags!", episodeTag.getTitle());
            }
            // download still & poster because episode has been selected here
            episodeTag.downloadPicture(mContext);
            episodeTag.downloadPoster(mContext);
        }
        log.debug("buildTag: {} {} {}", episodeTag.getShowTitle(), episodeTag.getShowId(), episodeTag.getTitle());
        return episodeTag;
    }

    private Bundle buildBundle(Map<String, EpisodeTags> allEpisodes, Bundle options) {
        Bundle bundle = null;
        if (options != null && options.containsKey(Scraper.ITEM_REQUEST_ALL_EPISODES) && !allEpisodes.isEmpty()) {
            bundle = new Bundle();
            for (Map.Entry<String, EpisodeTags> item : allEpisodes.entrySet())
                bundle.putParcelable(item.getKey(), item.getValue());
        }
        return bundle;
    }

    public static boolean isShowAlreadyKnown(Integer showId, Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        String[] baseProjection = {ScraperStore.Show.ONLINE_ID};
        Cursor cursor = contentResolver.query(
                ContentUris.withAppendedId(ScraperStore.Show.URI.ONLINE_ID, showId),
                baseProjection, null, null, null);
        Boolean isKnown = false;
        if (cursor != null) isKnown = cursor.moveToFirst();
        cursor.close();
        log.debug("isShowAlreadyKnown: {} {}", showId, isKnown);
        return isKnown;
    }

    @Override
    protected String internalGetPreferenceName() {
        return PREFERENCE_NAME;
    }

}
