/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thunkware.auto.valhalla.util;

import java.util.ArrayList;
import java.util.List;

/**
 * A trimmed-down subset of Apache Commons Lang {@code StringUtils} containing
 * only the substring-extraction-relative-to-other-strings methods
 * ({@link #substringBefore}, {@link #substringAfter}, {@link #substringBetween}
 * and their {@code Last}/{@code int} overloads). Kept self-contained so the
 * agent jar has no Apache Commons dependency.
 */
public final class StringUtils {

    private static final String EMPTY = "";

    private static final int INDEX_NOT_FOUND = -1;

    private StringUtils() {
    }

    /**
     * Gets the substring after the first occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The character (Unicode code point) to find.
     * @return The substring after the first occurrence of the specified
     *         character, {@code null} if null String input.
     */
    public static String substringAfter(final String str, final int find) {
        if (isEmpty(str)) {
            return str;
        }
        final int pos = str.indexOf(find);
        if (pos == INDEX_NOT_FOUND) {
            return EMPTY;
        }
        return str.substring(pos + Character.charCount(find));
    }

    /**
     * Gets the substring after the first occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The String to find, may be null.
     * @return The substring after the first occurrence of the specified
     *         string, {@code null} if null String input.
     */
    public static String substringAfter(final String str, final String find) {
        if (isEmpty(str)) {
            return str;
        }
        if (find == null) {
            return EMPTY;
        }
        final int pos = str.indexOf(find);
        if (pos == INDEX_NOT_FOUND) {
            return EMPTY;
        }
        return str.substring(pos + find.length());
    }

    /**
     * Gets the substring after the last occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The character (Unicode code point) to find.
     * @return The substring after the last occurrence of the specified
     *         character, {@code null} if null String input.
     */
    public static String substringAfterLast(final String str, final int find) {
        if (isEmpty(str)) {
            return str;
        }
        final int pos = str.lastIndexOf(find);
        if (pos == INDEX_NOT_FOUND || pos == str.length() - Character.charCount(find)) {
            return EMPTY;
        }
        return str.substring(pos + Character.charCount(find));
    }

    /**
     * Gets the substring after the last occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The String to find, may be null.
     * @return The substring after the last occurrence of the specified string,
     *         {@code null} if null String input.
     */
    public static String substringAfterLast(final String str, final String find) {
        if (isEmpty(str)) {
            return str;
        }
        if (isEmpty(find)) {
            return EMPTY;
        }
        final int pos = str.lastIndexOf(find);
        if (pos == INDEX_NOT_FOUND || pos == str.length() - find.length()) {
            return EMPTY;
        }
        return str.substring(pos + find.length());
    }

    /**
     * Gets the substring before the first occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The character (Unicode code point) to find.
     * @return The substring before the first occurrence of the specified
     *         character, {@code null} if null String input.
     */
    public static String substringBefore(final String str, final int find) {
        if (isEmpty(str)) {
            return str;
        }
        final int pos = str.indexOf(find);
        if (pos == INDEX_NOT_FOUND) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the substring before the first occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The String to find, may be null.
     * @return The substring before the first occurrence of the specified
     *         string, {@code null} if null String input.
     */
    public static String substringBefore(final String str, final String find) {
        if (isEmpty(str) || find == null) {
            return str;
        }
        if (find.isEmpty()) {
            return EMPTY;
        }
        final int pos = str.indexOf(find);
        if (pos == INDEX_NOT_FOUND) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the substring before the last occurrence of a separator. The
     * separator is not returned.
     *
     * @param str  The String to get a substring from, may be null.
     * @param find The String to find, may be null.
     * @return The substring before the last occurrence of the specified string,
     *         {@code null} if null String input.
     */
    public static String substringBeforeLast(final String str, final String find) {
        if (isEmpty(str) || isEmpty(find)) {
            return str;
        }
        final int pos = str.lastIndexOf(find);
        if (pos == INDEX_NOT_FOUND) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the String that is nested in between two instances of the same
     * String.
     *
     * @param str The String containing the substring, may be null.
     * @param tag The String before and after the substring, may be null.
     * @return The substring, {@code null} if no match.
     */
    public static String substringBetween(final String str, final String tag) {
        return substringBetween(str, tag, tag);
    }

    /**
     * Gets the String that is nested in between two Strings. Only the first
     * match is returned.
     *
     * @param str   The String containing the substring, may be null.
     * @param open  The String before the substring, may be null.
     * @param close The String after the substring, may be null.
     * @return The substring, {@code null} if no match.
     */
    public static String substringBetween(final String str, final String open, final String close) {
        if (str == null || open == null || close == null) {
            return null;
        }
        final int start = str.indexOf(open);
        if (start != INDEX_NOT_FOUND) {
            final int end = str.indexOf(close, start + open.length());
            if (end != INDEX_NOT_FOUND) {
                return str.substring(start + open.length(), end);
            }
        }
        return null;
    }

    /**
     * Searches a String for substrings delimited by a start and end tag,
     * returning all matching substrings in an array.
     *
     * @param str   The String containing the substrings, null returns null,
     *              empty returns empty.
     * @param open  The String identifying the start of the substring, empty
     *              returns null.
     * @param close The String identifying the end of the substring, empty
     *              returns null.
     * @return A String Array of substrings, or {@code null} if no match.
     */
    public static String[] substringsBetween(final String str, final String open, final String close) {
        if (str == null || isEmpty(open) || isEmpty(close)) {
            return null;
        }
        final int strLen = str.length();
        if (strLen == 0) {
            return new String[0];
        }
        final int closeLen = close.length();
        final int openLen = open.length();
        final List<String> list = new ArrayList<>();
        int pos = 0;
        while (pos < strLen - closeLen) {
            int start = str.indexOf(open, pos);
            if (start < 0) {
                break;
            }
            start += openLen;
            final int end = str.indexOf(close, start);
            if (end < 0) {
                break;
            }
            list.add(str.substring(start, end));
            pos = end + closeLen;
        }
        if (list.isEmpty()) {
            return null;
        }
        return list.toArray(new String[0]);
    }

    private static boolean isEmpty(final String str) {
        return str == null || str.isEmpty();
    }
}
