
package org.leeroy.mediascraper.preprocess;

import android.net.Uri;

import org.leeroy.filecorelibrary.FileUtils;
import org.leeroy.mediascraper.ShowUtils;
import org.leeroy.mediascraper.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.leeroy.filecorelibrary.FileUtils.getName;
import static org.leeroy.mediascraper.StringUtils.removeTrailingSlash;

/**
 * Matches all sorts of "Tv Show title S01E01/randomgarbage.mkv" and similar things
 */
class TvShowFolderMatcher extends TvShowMatcher {
    private static final Logger log = LoggerFactory.getLogger(TvShowFolderMatcher.class);

    public static TvShowFolderMatcher instance() {
        return INSTANCE;
    }

    private static final TvShowFolderMatcher INSTANCE =
            new TvShowFolderMatcher();

    private TvShowFolderMatcher() {
        // singleton
    }

    @Override
    public boolean matchesFileInput(Uri fileInput, Uri simplifiedUri) {
        return ShowUtils.isTvShow(FileUtils.getParentUrl(fileInput), null);
    }

    @Override
    public SearchInfo getFileInputMatch(Uri file, Uri simplifiedUri) {
        return getMatch(getName(FileUtils.getParentUrl(file)), file);
    }

    private static SearchInfo getMatch(String matchString, Uri file) {
        log.debug("getMatch: matchString {} file {}", matchString, file.getPath());
        // clean trailing "/" if exists
        matchString = removeTrailingSlash(matchString);
        // clean leading "/"
        matchString = getName(matchString);
        Map<String, String> showName = ShowUtils.parseShowName(matchString);
        if (showName != null) {
            String showTitle = showName.get(ShowUtils.SHOW);
            String season = showName.get(ShowUtils.SEASON);
            String episode = showName.get(ShowUtils.EPNUM);
            String year = showName.get(ShowUtils.YEAR);
            String countryOfOrigin = showName.get(ShowUtils.ORIGIN);
            int seasonInt = StringUtils.parseInt(season, 0);
            int episodeInt = StringUtils.parseInt(episode, 0);
            log.debug("getMatch: {} season {} episode {} year {} country {}", showTitle, season, episode, year, countryOfOrigin);
            return new TvShowSearchInfo(file, showTitle, seasonInt, episodeInt, year, countryOfOrigin);
        }
        return null;
    }

    @Override
    public String getMatcherName() {
        return "TVShowFolder";
    }

}
