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

package org.leeroy.mediaprovider;

import org.leeroy.environment.LeeroyFlixIntents;

/**
 * Intents for MediaCenter internal communications with the internal database
 */
public final class LeeroyFlixMediaIntent {
    private LeeroyFlixMediaIntent() { /* nothing */ }


    public static final String ACTION_VIDEO_SCANNER_SCAN_FILE = "leeroy.media.intent.action.VIDEO_SCANNER_SCAN_FILE";
    public static final String ACTION_VIDEO_SCANNER_REMOVE_FILE = "leeroy.media.intent.action.VIDEO_SCANNER_REMOVE_FILE";
    /**
     * Broadcast intent that forces the internal video / media database to
     * rescan the metadata of a file / folder. The path to the directory or file
     * to be rescanned goes into the Intent.mData field.
     * <p>
     * <code>
     * Intent intent = new Intent(MEDIASCANNER_METADATA_UPDATE);
     * intent.setData(Uri.fromFile(file));
     * </code>
     */
    public static final String ACTION_VIDEO_SCANNER_METADATA_UPDATE ="leeroy.media.intent.action.VIDEO_SCANNER_METADATA_UPDATE";
    public static final String ACTION_VIDEO_SCANNER_STORAGE_PERMISSION_GRANTED ="leeroy.media.intent.action.VIDEO_SCANNER_STORAGE_PERMISSION_GRANTED";
    public static final String ACTION_VIDEO_SCANNER_SCAN_STARTED = "leeroy.media.intent.action.VIDEO_SCANNER_SCAN_STARTED";
    public static final String ACTION_VIDEO_SCANNER_SCAN_FINISHED = "leeroy.media.intent.action.VIDEO_SCANNER_SCAN_FINISHED";

    public static final String ACTION_MUSIC_SCANNER_SCAN_FILE = "leeroy.media.intent.action.MUSIC_SCANNER_SCAN_FILE";
    public static final String ACTION_MUSIC_SCANNER_REMOVE_FILE = "leeroy.media.intent.action.MUSIC_SCANNER_REMOVE_FILE";
    public static final String ACTION_MUSIC_SCANNER_METADATA_UPDATE ="leeroy.media.intent.action.MUSIC_SCANNER_METADATA_UPDATE";

    public static final String ACTION_MUSIC_SCANNER_SCAN_STARTED = "leeroy.media.intent.action.MUSIC_SCANNER_SCAN_STARTED";
    public static final String ACTION_MUSIC_SCANNER_SCAN_FINISHED = "leeroy.media.intent.action.MUSIC_SCANNER_SCAN_FINISHED";

    public static final String ACTION_VIDEO_SCANNER_IMPORT_INCR = "leeroy.media.intent.action.VIDEO_SCANNER_IMPORT_INCR";

    public static boolean isVideoScanIntent(String action) {
        return LeeroyFlixIntents.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action) ||
                ACTION_VIDEO_SCANNER_SCAN_FILE.equals(action);
    }
    public static boolean isVideoRemoveIntent(String action) {
        return LeeroyFlixIntents.ACTION_MEDIA_SCANNER_REMOVE_FILE.equals(action) ||
                ACTION_VIDEO_SCANNER_REMOVE_FILE.equals(action);
    }
    public static boolean isMusicScanIntent(String action) {
        return LeeroyFlixIntents.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action) ||
                ACTION_MUSIC_SCANNER_SCAN_FILE.equals(action);
    }
    public static boolean isMusicRemoveIntent(String action) {
        return LeeroyFlixIntents.ACTION_MEDIA_SCANNER_REMOVE_FILE.equals(action) ||
                ACTION_MUSIC_SCANNER_REMOVE_FILE.equals(action);
    }
}
