// Copyright 2017 Archos SA
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

package com.archos.medialib;

import android.graphics.Bitmap;
import android.graphics.Rect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Subtitle {

    private static final Logger log = LoggerFactory.getLogger(Subtitle.class);

    private final int type;
    private static final int TYPE_TEXT_SUBTITLE = 1;
    private static final int TYPE_TIMED_TEXT_SUBTITLE = 2;
    private static final int TYPE_TIMED_BITMAP_SUBTITLE = 3;

    public enum SubtitleAlignment {
        BOTTOM_LEFT, BOTTOM_MID, BOTTOM_RIGHT, MID_LEFT, MID_MID, MID_RIGHT, TOP_LEFT, TOP_MID, TOP_RIGHT
    }

    public Subtitle(int type) {
        this.type = type;
    }

    public boolean isTimed() {
        return (this.type == TYPE_TIMED_TEXT_SUBTITLE || this.type == TYPE_TIMED_BITMAP_SUBTITLE) && getDuration() != -1;
    }
    
    public boolean isText() {
        return this.type == TYPE_TIMED_TEXT_SUBTITLE || this.type == TYPE_TEXT_SUBTITLE;
    }
    
    public boolean isBitmap() {
        return this.type == TYPE_TIMED_BITMAP_SUBTITLE;
    }
    
    public abstract String getText();
    public abstract Bitmap getBitmap();
    public abstract Rect getBounds();
    public abstract int getPosition();
    public abstract int getDuration();
    public abstract void setDuration(int duration);
    public abstract int getFrameWidth();
    public abstract int getFrameHeight();

    private SubtitleAlignment alignment = SubtitleAlignment.BOTTOM_MID; // Default alignment

    public SubtitleAlignment getAlignment() {
        return alignment;
    }

    public void setAlignment(SubtitleAlignment alignment) {
        if (log.isDebugEnabled()) log.debug("setAlignment: {}", alignment);
        this.alignment = alignment;
    }

    public static class TextSubtitle extends Subtitle {
        private final String text;
        public TextSubtitle(String text) {
            super(TYPE_TEXT_SUBTITLE);
            this.text = text;
        }

        public String getText() {
            return this.text;
        }
        public Bitmap getBitmap() { return null; }
        public int getFrameWidth() {
            return 0;
        }
        public int getFrameHeight() {
            return 0;
        }
        public Rect getBounds() {
            return null;
        }
        public int getPosition() {
            return -1;
        }
        public int getDuration() {
            return -1;
        }
        public void setDuration(int duration) {}
    }

    public abstract static class Timed extends Subtitle {
        private final int position;
        private int duration; // not final because sometimes duration is modified by empty next subtitle

        public Timed(int type, int position, int duration) {
            super(type);
            this.position = position;
            this.duration = duration;
        }

        public int getPosition() {
            return this.position;
        }
        public int getDuration() {
            return this.duration;
        }
        public void setDuration(int duration) { this.duration = duration; }
    }

    public static class TimedTextSubtitle extends Timed {
        private final String text;

        public TimedTextSubtitle(int position, int duration, String text) {
            super(TYPE_TIMED_TEXT_SUBTITLE, position, duration);
            this.text = text;
        }

        public String getText() {
            return this.text;
        }
        public Bitmap getBitmap() {
            return null;
        }
        public int getFrameWidth() {
            return 0;
        }
        public int getFrameHeight() {
            return 0;
        }
        public Rect getBounds() {
            return null;
        }
    }

    public static class TimedBitmapSubtitle extends Timed {
        private final Bitmap bitmap;
        private final Rect bounds;
        private final int frameWidth;
        private final int frameHeight;

        public TimedBitmapSubtitle(int position, int duration, int leftCorner, int topCorner, int originalWidth, int originalHeight, Bitmap bitmap) {
            super(TYPE_TIMED_BITMAP_SUBTITLE, position, duration);
            this.bitmap = bitmap;
            this.frameWidth = originalWidth;
            this.frameHeight = originalHeight;
            this.bounds = new Rect(leftCorner, topCorner, leftCorner + bitmap.getWidth(), topCorner + bitmap.getHeight());
            if (log.isDebugEnabled()) log.debug("TimedBitmapSubtitle: position={}, duration={}, framewidth={}, frameheight={}, bounds={}", position, duration, frameWidth, frameHeight, bounds);
        }
        public String getText() {
            return null;
        }
        public Bitmap getBitmap() {
            return this.bitmap;
        }
        public Rect getBounds() {
            return this.bounds;
        }
        public int getFrameWidth() { return this.frameWidth; }
        public int getFrameHeight() { return this.frameHeight; }
    }

    // from jni
    public static Object createTimedTextSubtitle(int position, int duration, String text) {
        return new Subtitle.TimedTextSubtitle(position, duration, text);
    }

    public static Object createTimedBitmapSubtitle(int position, int duration, int leftCorner, int topCorner, int originalWidth, int originalHeight, Bitmap bitmap) {
        return new Subtitle.TimedBitmapSubtitle(position, duration, leftCorner, topCorner, originalWidth, originalHeight, bitmap);
    }
}