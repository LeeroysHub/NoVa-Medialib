// Copyright 2017 LeeroyFlix
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

import org.leeroy.mediascraper.ScrapeStatus;
import org.leeroy.mediascraper.SearchResult;

import java.util.Collections;
import java.util.List;

public class SearchMovieResult {
    public static final List<SearchResult> EMPTY_LIST = Collections.<SearchResult>emptyList();
    public List<SearchResult> result;
    public ScrapeStatus status;
    public Throwable reason;
    public SearchMovieResult() {
        this.result = EMPTY_LIST;
    }
}
