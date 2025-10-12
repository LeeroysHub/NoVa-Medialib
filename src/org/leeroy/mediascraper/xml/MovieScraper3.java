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

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import org.leeroy.medialib.R;
import org.leeroy.mediascraper.MovieTags;
import org.leeroy.mediascraper.ScrapeDetailResult;
import org.leeroy.mediascraper.ScrapeSearchResult;
import org.leeroy.mediascraper.ScrapeStatus;
import org.leeroy.mediascraper.Scraper;
import org.leeroy.mediascraper.ScraperCache;
import org.leeroy.mediascraper.SearchResult;
import org.leeroy.mediascraper.preprocess.MovieSearchInfo;
import org.leeroy.mediascraper.preprocess.SearchInfo;
import org.leeroy.mediascraper.themoviedb3.CollectionInfo;
import org.leeroy.mediascraper.themoviedb3.CollectionResult;
import org.leeroy.mediascraper.themoviedb3.ImageConfiguration;
import org.leeroy.mediascraper.themoviedb3.MovieCollection;
import org.leeroy.mediascraper.themoviedb3.MovieId2;
import org.leeroy.mediascraper.themoviedb3.MovieIdDescription2;
import org.leeroy.mediascraper.themoviedb3.MovieIdResult;
import org.leeroy.mediascraper.themoviedb3.MyTmdb;
import org.leeroy.mediascraper.themoviedb3.SearchMovie2;
import org.leeroy.mediascraper.themoviedb3.SearchMovieResult;
import com.uwetrottmann.tmdb2.services.CollectionsService;
import com.uwetrottmann.tmdb2.services.MoviesService;
import com.uwetrottmann.tmdb2.services.SearchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cache;

import static org.leeroy.mediascraper.MovieTags.isCollectionAlreadyKnown;
import static org.leeroy.mediascraper.themoviedb3.MovieCollectionImages.downloadCollectionImage;

import androidx.preference.PreferenceManager;


public class MovieScraper3 extends BaseScraper2 {
    private static final String PREFERENCE_NAME = "themoviedb.org";

    private static final Logger log = LoggerFactory.getLogger(MovieScraper3.class);

    // Add caching for OkHttpClient so that queries for episodes from a same tvshow will get a boost in resolution
    static Cache cache;

    static MyTmdb tmdb = null;
    static SearchService searchService = null;
    static MoviesService moviesService = null;
    static CollectionsService collectionService = null;

    static String apiKey = null;

    public MovieScraper3(Context context) {
        super(context);
        // ensure cache is initialized
        synchronized (MovieScraper3.class) {
            cache = ScraperCache.getCache(context);
            apiKey = context.getString(R.string.tmdb_api_key);
        }
    }

    public static void reauth() {
        tmdb = new MyTmdb(apiKey, cache);
    }

    @Override
    public ScrapeSearchResult getMatches2(SearchInfo info, int maxItems) {
        // check input
        if (info == null || !(info instanceof MovieSearchInfo)) {
            log.error("bad search info: {}", info == null ? "null" : "tvshow in movie scraper");
            return new ScrapeSearchResult(null, true, ScrapeStatus.ERROR, null);
        }
        MovieSearchInfo searchInfo = (MovieSearchInfo) info;
        log.debug("getMatches2: movie search:{}", searchInfo.getName());
        if (tmdb == null) reauth();
        if (searchService == null) searchService = tmdb.searchService();
        // get configured language
        String language = Scraper.getLanguage(mContext);
        log.debug("movie search:{} year:{} language:{}", searchInfo.getName(), searchInfo.getYear(), language);
        
        //Check for UPNP and SMB differences, make sure we have a valid title.
        log.debug("movie search:" + searchInfo.getName() + " year:" + searchInfo.getYear() + " language:" + language);
        String searchQuery = searchInfo.getSearchSuggestion().contains("null") ? searchInfo.getName() : searchInfo.getSearchSuggestion();
        if (searchQuery.toLowerCase().contains("null")) {
            searchQuery = searchInfo.getFile().toString();
        }

        // Extract 4-digit year and trim string (If its still there.)
        Pattern yearPattern = Pattern.compile("\\b(\\d{4})\\b");
        Matcher matcher = yearPattern.matcher(searchQuery);
        String year = null;
        if (matcher.find()) {
            year = matcher.group(1); // Extracted year
            int yearIndex = matcher.start(); // Index where year starts
            searchQuery = searchQuery.substring(0, yearIndex).trim(); // Keep string up to year
            // You can use 'year' variable as needed
        } else {
            year = searchInfo.getYear();
        }

        //SEARCH TMDB FOR THE MOVIE!
        SearchMovieResult searchResult = SearchMovie2.search(searchQuery, language, year, maxItems, searchService, adultScrape);
        
        // TODO: this triggers scrape for all search results, is this intended?
        if (searchResult.status == ScrapeStatus.OKAY) {
            for (SearchResult result : searchResult.result) {
                result.setScraper(this);
                result.setFile(searchInfo.getFile());
            }
        }
        return new ScrapeSearchResult(searchResult.result, true, searchResult.status, searchResult.reason);
    }

    @Override
    protected ScrapeDetailResult getDetailsInternal(SearchResult result, Bundle options) {
        // TODO: why it searches every first level result?
        String language = Scraper.getLanguage(mContext);
        log.debug("getDetailsInternal: language={}", language);

        long movieId = result.getId();
        Uri searchFile = result.getFile();

        if (tmdb == null) reauth();
        if (moviesService == null) moviesService = tmdb.moviesService();

        // get base info
        MovieIdResult search = MovieId2.getBaseInfo(movieId, language, moviesService, mContext);
        if (search.status != ScrapeStatus.OKAY) {
            return new ScrapeDetailResult(search.tag, true, null, search.status, search.reason);
        }

        MovieTags tag = search.tag;
        tag.setFile(searchFile);

        // TODO MARC remove?
        /*
        ScraperImage defaultPoster = tag.getDefaultPoster();
        if (defaultPoster != null) {
            tag.setCover(defaultPoster.getLargeFileF());
        }
         */

        // MovieCollection poster/backdrops and information are handled in the MovieTag because it is easier
        if (tag.getCollectionId() != -1 && ! isCollectionAlreadyKnown(tag.getCollectionId(), mContext)) { // in presence of a movie collection/saga
            if (collectionService == null) collectionService = tmdb.collectionService();
            CollectionResult collectionResult = MovieCollection.getInfo(tag.getCollectionId(), language, collectionService);
            if (collectionResult.status == ScrapeStatus.OKAY && collectionResult.collectionInfo != null) {
                CollectionInfo collectionInfo = collectionResult.collectionInfo;
                if (collectionInfo.name != null) tag.setCollectionName(collectionInfo.name);
                if (collectionInfo.description != null) tag.setCollectionDescription(collectionInfo.description);
                if (collectionInfo.poster != null) tag.setCollectionPosterPath(collectionInfo.poster);
                if (collectionInfo.backdrop != null) tag.setCollectionBackdropPath(collectionInfo.backdrop);
            }
            downloadCollectionImage(tag,
                    ImageConfiguration.PosterSize.W342,    // large poster
                    ImageConfiguration.PosterSize.W92,     // thumb poster
                    ImageConfiguration.BackdropSize.W1280, // large bd
                    ImageConfiguration.BackdropSize.W300,  // thumb bd
                    searchFile.toString(), mContext);
        }

        // if there was no movie description in the native language get it from default
        if (tag.getPlot() == null || tag.getPlot().isEmpty()) {
            log.debug("ScrapeDetailResult: getting description in en because plot non existent in {}", language);
            MovieIdDescription2.addDescription(movieId, tag, moviesService);
        }
        tag.downloadPoster(mContext);
        // TODO MARC ?
        tag.downloadBackdrop(mContext);
        return new ScrapeDetailResult(tag, true, null, ScrapeStatus.OKAY, null);
    }

    @Override
    protected String internalGetPreferenceName() {
        return PREFERENCE_NAME;
    }
}
