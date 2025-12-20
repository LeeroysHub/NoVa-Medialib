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

package org.leeroy.mediascraper.themoviedb3;

import android.util.LruCache;
//import android.util.Pair;

import org.leeroy.filecorelibrary.FileUtils;
import org.leeroy.mediascraper.ScrapeStatus;
import org.leeroy.mediascraper.ShowUtils;
import org.leeroy.mediascraper.preprocess.TvShowSearchInfo;
import org.leeroy.mediascraper.xml.ShowScraper4;
import com.uwetrottmann.tmdb2.entities.TvShowResultsPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import retrofit2.Response;

import static org.leeroy.mediascraper.preprocess.ParseUtils.yearExtractor;

// Search Show for name query for year in language (ISO 639-1 code)
public class SearchShow {
    private static final Logger log = LoggerFactory.getLogger(SearchShow.class);

    // Benchmarks tells that with tv shows sorted in folders, size of 200 or 20 or even 10 provides the same cacheHits on fake collection of 30k episodes, 250 shows
    private final static LruCache<String, Response<TvShowResultsPage>> showCache = new LruCache<>(50);

    public static SearchShowResult search(TvShowSearchInfo searchInfo, String language, int resultLimit, final boolean adultScrape, ShowScraper4 showScraper, MyTmdb tmdb) {
        SearchShowResult myResult = new SearchShowResult();
        Response<TvShowResultsPage> response = null;
        boolean authIssue = false;
        boolean notFoundIssue = true;
        boolean isResponseOk = false;
        boolean isResponseEmpty = false;
        boolean serviceError = false;
        String showKey = null;
        String name;
        //if (log.isDebugEnabled()) log.debug("search: quering tmdb for {} year {} in {}, resultLimit={}", searchInfo.getShowName(), searchInfo.getFirstAiredYear(), language, resultLimit);
        try {
            //Get the Year from the Show name, if we have it (not likely!)
            Integer year = null;
            if (searchInfo.getFirstAiredYear() != null) {
                try {
                    year = Integer.parseInt(searchInfo.getFirstAiredYear());
                } catch (NumberFormatException nfe) {
                    log.warn("search: not valid year int {}", searchInfo.getFirstAiredYear());
                }
            }

            //Get the Parent Folder, and check for a year.
            if (searchInfo.getOriginalUri() != null ) {
                String parentPath = FileUtils.getName(FileUtils.getParentUrl(FileUtils.getParentUrl(searchInfo.getOriginalUri())));

                //Look for any years in the Parent Folder
                String reversed = new StringBuilder(parentPath).reverse().toString();
                Pattern yearPattern = Pattern.compile("\\b(\\d{4})\\b");
                Matcher matcher = yearPattern.matcher(reversed);

                // Current Calendar year.
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);

                //Loop backwards through the string until we have a plausible year,
                //Make sure we have at least a 2 DIGIT word left (IF Movie!)
                while (matcher.find()) {
                    String candidateYear = new StringBuilder(matcher.group(1)).reverse().toString();
                    int parsedYear = Integer.parseInt(candidateYear);

                    if (parsedYear >= 1900 && parsedYear <= currentYear) {
                        int cutIndex = parentPath.length() - matcher.start() - 4;
                        if (cutIndex >= 2) {
                            parentPath = parentPath.substring(0, cutIndex).trim();
                            year = Integer.parseInt(candidateYear);
                        }
                        break;
                    }
                }
            }

            //Grab the show name now, and do the TMDB seerch.
            String searchQueryString = searchInfo.getShowName();
            showKey = ShowUtils.cleanUpName(searchQueryString.toLowerCase()) + "|" + language;
            //if (log.isDebugEnabled()) log.debug("SearchShowResult: cache showKey {}", showKey);
            response = showCache.get(showKey);
            //if (log.isTraceEnabled()) debugLruCache(showCache);
            if (response == null) {
                //if (log.isDebugEnabled()) log.debug("SearchShowResult: no boost for {} year {}", searchInfo.getShowName(), year);
                // adult search false by default
                response = tmdb.searchService().tv(searchQueryString, 1, language, year, false).execute();
                if (response.code() != 404) notFoundIssue = false; // this is an AND
                // Check https://developer.themoviedb.org/docs/errors
                switch (response.code()) {
                    case 401 -> authIssue = true; // this is an OR
                    case 404 -> notFoundIssue = true; // this is an AND
                    case 500, 503, 504 -> serviceError = true;
                }
                if (response.isSuccessful()) isResponseOk = true;
                if (response.body() == null)
                    isResponseEmpty = true;
                else {
                    if (response.body().total_results == 0) notFoundIssue = true;

                    //We have a show, put it in cache before returning.
                    if (isResponseOk) {
                        //log.debug("search: inserting in showCache {} and response ", showKey);
                        showCache.put(showKey, response);
                    }
                }
            } else {
                //log.debug("search: boost using cached searched show for {}", searchInfo.getShowName());
                isResponseOk = true;
                notFoundIssue = false;
                if (response.body() == null) isResponseEmpty = true;
            }
            if (authIssue) {
                //log.debug("search: auth error");
                myResult.status = ScrapeStatus.AUTH_ERROR;
                myResult.result = SearchShowResult.EMPTY_LIST;
                ShowScraper4.reauth();
                return myResult;
            }
            if (notFoundIssue || serviceError) {
                //log.debug("search: not found");
                myResult.result = SearchShowResult.EMPTY_LIST;
                if (serviceError) myResult.status = ScrapeStatus.ERROR;
                else myResult.status = ScrapeStatus.NOT_FOUND;
            } else {
                if (isResponseEmpty) {
                    //log.debug("search: error");
                    myResult.result = SearchShowResult.EMPTY_LIST;
                    myResult.status = ScrapeStatus.ERROR_PARSER;
                } else {
                    myResult.result = SearchShowParser.getResult(
                            (isResponseOk) ? response : null,
                            searchInfo, year, language, resultLimit, showScraper);
                    myResult.status = ScrapeStatus.OKAY;
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled())
                log.error("search: caught IOException {}", e.getMessage(), e);
            else
                log.error("search: caught IOException");
            myResult.result = SearchShowResult.EMPTY_LIST;
            myResult.status = ScrapeStatus.ERROR_PARSER;
            myResult.reason = e;
        }
        return myResult;
    }

    public static void debugLruCache(LruCache<String, Response<TvShowResultsPage>> lruCache) {
        log.debug("debugLruCache: size={}", lruCache.size());
        log.debug("debugLruCache: putCount={}", lruCache.putCount());
        log.debug("debugLruCache: hitCount={}", lruCache.hitCount());
        log.debug("debugLruCache: missCount={}", lruCache.missCount());
        log.debug("debugLruCache: evictionCount={}", lruCache.evictionCount());
    }

}
