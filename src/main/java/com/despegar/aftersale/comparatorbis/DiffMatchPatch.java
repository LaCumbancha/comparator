package com.despegar.aftersale.comparatorbis;

/*
 * Diff Match and Patch
 *
 * Copyright 2006 Google Inc.
 * http://code.google.com/p/google-diff-match-patch/
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc, 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Stack;
import java.util.ListIterator;
import java.util.regex.*;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;

/*
 * Functions for diff, match and patch.
 * Computes the difference between two texts to create a patch.
 * Applies the patch onto another text, allowing for errors.
 *
 * @author fraser@google.com (Neil Fraser)
 */

/**
 * Class containing the diff, match and patch methods.
 * Also contains the behaviour settings.
 */
public class DiffMatchPatch {

    // Defaults.
    // Set these on your DiffMatchPatch instance to override the defaults.

    // Number of seconds to map a diff before giving up.  (0 for infinity)
    public float Diff_Timeout = 1.0f;
    // Cost of an empty edit operation in terms of edit characters.
    public short Diff_EditCost = 4;
    // Tweak the relative importance (0.0 = accuracy, 1.0 = proximity)
    public float Match_Balance = 0.5f;
    // At what point is no match declared (0.0 = perfection, 1.0 = very loose)
    public float Match_Threshold = 0.5f;
    // The min and max cutoffs used when computing text lengths.
    public int Match_MinLength = 100;
    public int Match_MaxLength = 1000;
    // Chunk size for context length.
    public short Patch_Margin = 4;

    // The number of bits in an int.
    private int Match_MaxBits = 32;


    //  DIFF FUNCTIONS


    /**-
     * The data structure representing a diff is a Linked list of Diff objects:
     * {Diff(Operation.DELETE, "Hello"), Diff(Operation.INSERT, "Goodbye"),
     *  Diff(Operation.EQUAL, " world.")}
     * which means: delete "Hello", add "Goodbye" and keep " world."
     */
    public enum Operation {
        DELETE, INSERT, EQUAL
    }


    /**
     * Find the differences between two texts.
     * Run a faster slightly less optimal diff
     * This method allows the 'checklines' of diff_main() to be optional.
     * Most of the time checklines is wanted, so default to true.
     * @param text1 Old string to be diffed
     * @param text2 New string to be diffed
     * @return Linked List of Diff objects
     */
    public LinkedList<Diff> diff_main(String text1, String text2) {
        return diff_main(text1, text2, true);
    }

    /**
     * Find the differences between two texts.  Simplifies the problem by
     * stripping any common prefix or suffix off the texts before diffing.
     * @param text1 Old string to be diffed
     * @param text2 New string to be diffed
     * @param checklines Speedup flag.  If false, then don't run a
     *     line-level diff first to identify the changed areas.
     *     If true, then run a faster slightly less optimal diff
     * @return Linked List of Diff objects
     */
    public LinkedList<Diff> diff_main(String text1, String text2,
                                      boolean checklines) {
        // Check for equality (speedup)
        LinkedList<Diff> diffs;
        if (text1.equals(text2)) {
            diffs = new LinkedList<Diff>();
            diffs.add(new Diff(Operation.EQUAL, text1));
            return diffs;
        }

        // Trim off common prefix (speedup)
        int commonlength = diff_commonPrefix(text1, text2);
        String commonprefix = text1.substring(0, commonlength);
        text1 = text1.substring(commonlength);
        text2 = text2.substring(commonlength);

        // Trim off common suffix (speedup)
        commonlength = diff_commonSuffix(text1, text2);
        String commonsuffix = text1.substring(text1.length() - commonlength);
        text1 = text1.substring(0, text1.length() - commonlength);
        text2 = text2.substring(0, text2.length() - commonlength);

        // Compute the diff on the middle block
        diffs = diff_compute(text1, text2, checklines);

        // Restore the prefix and suffix
        if (!commonprefix.equals("")) {
            diffs.addFirst(new Diff(Operation.EQUAL, commonprefix));
        }
        if (!commonsuffix.equals("")) {
            diffs.addLast(new Diff(Operation.EQUAL, commonsuffix));
        }

        diff_cleanupMerge(diffs);
        return diffs;
    }


    /**
     * Find the differences between two texts.
     * @param text1 Old string to be diffed
     * @param text2 New string to be diffed
     * @param checklines Speedup flag.  If false, then don't run a
     *     line-level diff first to identify the changed areas.
     *     If true, then run a faster slightly less optimal diff
     * @return Linked List of Diff objects
     */
    public LinkedList<Diff> diff_compute(String text1, String text2,
                                         boolean checklines) {
        LinkedList<Diff> diffs = new LinkedList<Diff>();

        if (text1.equals("")) {
            // Just add some text (speedup)
            diffs.add(new Diff(Operation.INSERT, text2));
            return diffs;
        }

        if (text2.equals("")) {
            // Just delete some text (speedup)
            diffs.add(new Diff(Operation.DELETE, text1));
            return diffs;
        }

        String longtext = text1.length() > text2.length() ? text1 : text2;
        String shorttext = text1.length() > text2.length() ? text2 : text1;
        int i = longtext.indexOf(shorttext);
        if (i != -1) {
            // Shorter text is inside the longer text (speedup)
            Operation op = (text1.length() > text2.length()) ?
                    Operation.DELETE : Operation.INSERT;
            diffs.add(new Diff(op, longtext.substring(0, i)));
            diffs.add(new Diff(Operation.EQUAL, shorttext));
            diffs.add(new Diff(op, longtext.substring(i + shorttext.length())));
            return diffs;
        }
        longtext = shorttext = null;  // Garbage collect

        // Check to see if the problem can be split in two.
        String[] hm = diff_halfMatch(text1, text2);
        if (hm != null) {
            // A half-match was found, sort out the return data.
            String text1_a = hm[0];
            String text1_b = hm[1];
            String text2_a = hm[2];
            String text2_b = hm[3];
            String mid_common = hm[4];
            // Send both pairs off for separate processing.
            LinkedList<Diff> diffs_a = diff_main(text1_a, text2_a, checklines);
            LinkedList<Diff> diffs_b = diff_main(text1_b, text2_b, checklines);
            // Merge the results.
            diffs = diffs_a;
            diffs.add(new Diff(Operation.EQUAL, mid_common));
            diffs.addAll(diffs_b);
            return diffs;
        }

        // Perform a real diff.
        if (checklines && text1.length() + text2.length() < 250) {
            checklines = false;  // Too trivial for the overhead.
        }
        ArrayList<String> linearray = null;
        if (checklines) {
            // Scan the text on a line-by-line basis first.
            Object b[] = diff_linesToChars(text1, text2);
            text1 = (String) b[0];
            text2 = (String) b[1];
            linearray = (ArrayList<String>) b[2];
        }

        diffs = diff_map(text1, text2);
        if (diffs == null) {
            // No acceptable result.
            diffs = new LinkedList<Diff>();
            diffs.add(new Diff(Operation.DELETE, text1));
            diffs.add(new Diff(Operation.INSERT, text2));
        }

        if (checklines) {
            // Convert the diff back to original text.
            diff_charsToLines(diffs, linearray);
            // Eliminate freak matches (e.g. blank lines)
            diff_cleanupSemantic(diffs);

            // Rediff any replacement blocks, this time character-by-character.
            // Add a dummy entry at the end.
            diffs.add(new Diff(Operation.EQUAL, ""));
            int count_delete = 0;
            int count_insert = 0;
            StringBuilder text_delete = new StringBuilder();
            StringBuilder text_insert = new StringBuilder();
            ListIterator<Diff> pointer = diffs.listIterator();
            Diff thisDiff = pointer.next();
            while (thisDiff != null) {
                if (thisDiff.operation == Operation.INSERT) {
                    count_insert++;
                    text_insert.append(thisDiff.text);
                } else if (thisDiff.operation == Operation.DELETE) {
                    count_delete++;
                    text_delete.append(thisDiff.text);
                } else {
                    // Upon reaching an equality, check for prior redundancies.
                    if (count_delete >= 1 && count_insert >= 1) {
                        // Delete the offending records and add the merged ones.
                        pointer.previous();
                        for (int j = 0; j < count_delete + count_insert; j++) {
                            pointer.previous();
                            pointer.remove();
                        }
                        for (Diff newDiff : diff_main(text_delete.toString(), text_insert.toString(), false)) {
                            pointer.add(newDiff);
                        }
                    }
                    count_insert = 0;
                    count_delete = 0;
                    text_delete = new StringBuilder();
                    text_insert = new StringBuilder();
                }
                thisDiff = pointer.hasNext() ? pointer.next() : null;
            }
            diffs.removeLast();  // Remove the dummy entry at the end.
        }
        return diffs;
    }


    /**
     * Split two texts into a list of strings.  Reduce the texts to a string of
     * hashes where each Unicode character represents one line.
     * @param text1 First string
     * @param text2 Second string
     * @return Three element Object array, containing the encoded text1, the
     *     encoded text2 and the List of unique strings.  The zeroth element
     *     of the List of unique strings is intentionally blank.
     */
    protected Object[] diff_linesToChars(String text1, String text2) {
        List<String> linearray = new ArrayList<String>();
        Map<String, Integer> linehash = new HashMap<String, Integer>();
        // e.g. linearray[4] == "Hello\n"
        // e.g. linehash.get("Hello\n") == 4

        // "\x00" is a valid character, but various debuggers don't like it.
        // So we'll insert a junk entry to avoid generating a null character.
        linearray.add("");

        String chars1 = diff_linesToCharsMunge(text1, linearray, linehash);
        String chars2 = diff_linesToCharsMunge(text2, linearray, linehash);
        return new Object[]{chars1, chars2, linearray};
    }


    /**
     * Split a text into a list of strings.  Reduce the texts to a string of
     * hashes where each Unicode character represents one line.
     * @param text String to encode
     * @param linearray List of unique strings
     * @param linehash Map of strings to indices
     * @return Encoded string
     */
    private String diff_linesToCharsMunge(String text, List<String> linearray,
                                          Map<String, Integer> linehash) {
        int i;
        String line;
        StringBuilder chars = new StringBuilder();
        // text.split('\n') would work fine, but would temporarily double our
        // memory footprint for minimal speed improvement.
        while (text.length() != 0) {
            i = text.indexOf('\n');
            if (i == -1) {
                i = text.length() - 1;
            }
            line = text.substring(0, i + 1);
            text = text.substring(i + 1);
            if (linehash.containsKey(line)) {
                chars.append(String.valueOf((char) (int) linehash.get(line)));
            } else {
                linearray.add(line);
                linehash.put(line, linearray.size() - 1);
                chars.append(String.valueOf((char) (linearray.size() - 1)));
            }
        }
        return chars.toString();
    }


    /**
     * Rehydrate the text in a diff from a string of line hashes to real lines of
     * text.
     * @param diffs LinkedList of Diff objects
     * @param linearray List of unique strings
     */
    protected void diff_charsToLines(LinkedList<Diff> diffs,
                                     List<String> linearray) {
        StringBuilder text;
        for (Diff diff : diffs) {
            text = new StringBuilder();
            for (int y = 0; y < diff.text.length(); y++) {
                text.append(linearray.get(diff.text.charAt(y)));
            }
            diff.text = text.toString();
        }
    }


    /**
     * Explore the intersection points between the two texts.
     * @param text1 Old string to be diffed
     * @param text2 New string to be diffed
     * @return LinkedList of Diff objects or null if no diff available
     */
    protected LinkedList<Diff> diff_map(String text1, String text2) {
        long ms_end = System.currentTimeMillis() + (long) (Diff_Timeout * 1000);
        int max_d = (text1.length() + text2.length()) / 2;
        List<Set<String>> v_map1 = new ArrayList<Set<String>>();
        List<Set<String>> v_map2 = new ArrayList<Set<String>>();
        Map<Integer, Integer> v1 = new HashMap<Integer, Integer>();
        Map<Integer, Integer> v2 = new HashMap<Integer, Integer>();
        v1.put(1, 0);
        v2.put(1, 0);
        int x, y;
        String footstep;  // Used to track overlapping paths.
        Map<String, Integer> footsteps = new HashMap<String, Integer>();
        boolean done = false;
        // If the total number of characters is odd, then the front path will
        // collide with the reverse path.
        boolean front = ((text1.length() + text2.length()) % 2 == 1);
        for (int d = 0; d < max_d; d++) {
            // Bail out if timeout reached.
            if (Diff_Timeout > 0 && System.currentTimeMillis() > ms_end) {
                return null;
            }

            // Walk the front path one step.
            v_map1.add(new HashSet<String>());  // Adds at index 'd'.
            for (int k = -d; k <= d; k += 2) {
                if (k == -d || k != d && v1.get(k - 1) < v1.get(k + 1)) {
                    x = v1.get(k + 1);
                } else {
                    x = v1.get(k - 1) + 1;
                }
                y = x - k;
                footstep = x + "," + y;
                if (front && (footsteps.containsKey(footstep))) {
                    done = true;
                }
                if (!front) {
                    footsteps.put(footstep, d);
                }
                while (!done && x < text1.length() && y < text2.length()
                        && text1.charAt(x) == text2.charAt(y)) {
                    x++;
                    y++;
                    footstep = x + "," + y;
                    if (front && (footsteps.containsKey(footstep))) {
                        done = true;
                    }
                    if (!front) {
                        footsteps.put(footstep, d);
                    }
                }
                v1.put(k, x);
                v_map1.get(d).add(x + "," + y);
                if (done) {
                    // Front path ran over reverse path.
                    v_map2 = v_map2.subList(0, footsteps.get(footstep) + 1);
                    LinkedList<Diff> a = diff_path1(v_map1, text1.substring(0, x),
                            text2.substring(0, y));
                    a.addAll(diff_path2(v_map2, text1.substring(x), text2.substring(y)));
                    return a;
                }
            }

            // Walk the reverse path one step.
            v_map2.add(new HashSet<String>());  // Adds at index 'd'.
            for (int k = -d; k <= d; k += 2) {
                if (k == -d || k != d && v2.get(k - 1) < v2.get(k + 1)) {
                    x = v2.get(k + 1);
                } else {
                    x = v2.get(k - 1) + 1;
                }
                y = x - k;
                footstep = (text1.length() - x) + "," + (text2.length() - y);
                if (!front && (footsteps.containsKey(footstep))) {
                    done = true;
                }
                if (front) {
                    footsteps.put(footstep, d);
                }
                while (!done && x < text1.length() && y < text2.length()
                        && text1.charAt(text1.length() - x - 1)
                        == text2.charAt(text2.length() - y - 1)) {
                    x++;
                    y++;
                    footstep = (text1.length() - x) + "," + (text2.length() - y);
                    if (!front && (footsteps.containsKey(footstep))) {
                        done = true;
                    }
                    if (front) {
                        footsteps.put(footstep, d);
                    }
                }
                v2.put(k, x);
                v_map2.get(d).add(x + "," + y);
                if (done) {
                    // Reverse path ran over front path.
                    v_map1 = v_map1.subList(0, footsteps.get(footstep) + 1);
                    LinkedList<Diff> a
                            = diff_path1(v_map1, text1.substring(0, text1.length() - x),
                            text2.substring(0, text2.length() - y));
                    a.addAll(diff_path2(v_map2, text1.substring(text1.length() - x),
                            text2.substring(text2.length() - y)));
                    return a;
                }
            }
        }
        // Number of diffs equals number of characters, no commonality at all.
        return null;
    }


    /**
     * Work from the middle back to the start to determine the path.
     * @param v_map List of path sets.
     * @param text1 Old string fragment to be diffed
     * @param text2 New string fragment to be diffed
     * @return LinkedList of Diff objects
     */
    protected LinkedList<Diff> diff_path1(List<Set<String>> v_map,
                                          String text1, String text2) {
        LinkedList<Diff> path = new LinkedList<Diff>();
        int x = text1.length();
        int y = text2.length();
        Operation last_op = null;
        for (int d = v_map.size() - 2; d >= 0; d--) {
            while (true) {
                if (v_map.get(d).contains((x - 1) + "," + y)) {
                    x--;
                    if (last_op == Operation.DELETE) {
                        path.getFirst().text = text1.charAt(x) + path.getFirst().text;
                    } else {
                        path.addFirst(new Diff(Operation.DELETE,
                                text1.substring(x, x + 1)));
                    }
                    last_op = Operation.DELETE;
                    break;
                } else if (v_map.get(d).contains(x + "," + (y - 1))) {
                    y--;
                    if (last_op == Operation.INSERT) {
                        path.getFirst().text = text2.charAt(y) + path.getFirst().text;
                    } else {
                        path.addFirst(new Diff(Operation.INSERT,
                                text2.substring(y, y + 1)));
                    }
                    last_op = Operation.INSERT;
                    break;
                } else {
                    x--;
                    y--;
                    assert (text1.charAt(x) == text2.charAt(y))
                            : "No diagonal.  Can't happen. (diff_path1)";
                    if (last_op == Operation.EQUAL) {
                        path.getFirst().text = text1.charAt(x) + path.getFirst().text;
                    } else {
                        path.addFirst(new Diff(Operation.EQUAL, text1.substring(x, x + 1)));
                    }
                    last_op = Operation.EQUAL;
                }
            }
        }
        return path;
    }


    /**
     * Work from the middle back to the end to determine the path.
     * @param v_map List of path sets.
     * @param text1 Old string fragment to be diffed
     * @param text2 New string fragment to be diffed
     * @return LinkedList of Diff objects
     */
    protected LinkedList<Diff> diff_path2(List<Set<String>> v_map,
                                          String text1, String text2) {
        LinkedList<Diff> path = new LinkedList<Diff>();
        int x = text1.length();
        int y = text2.length();
        Operation last_op = null;
        for (int d = v_map.size() - 2; d >= 0; d--) {
            while (true) {
                if (v_map.get(d).contains((x - 1) + "," + y)) {
                    x--;
                    if (last_op == Operation.DELETE) {
                        path.getLast().text += text1.charAt(text1.length() - x - 1);
                    } else {
                        path.addLast(new Diff(Operation.DELETE,
                                text1.substring(text1.length() - x - 1, text1.length() - x)));
                    }
                    last_op = Operation.DELETE;
                    break;
                } else if (v_map.get(d).contains(x + "," + (y - 1))) {
                    y--;
                    if (last_op == Operation.INSERT) {
                        path.getLast().text += text2.charAt(text2.length() - y - 1);
                    } else {
                        path.addLast(new Diff(Operation.INSERT,
                                text2.substring(text2.length() - y - 1, text2.length() - y)));
                    }
                    last_op = Operation.INSERT;
                    break;
                } else {
                    x--;
                    y--;
                    assert (text1.charAt(text1.length() - x - 1)
                            == text2.charAt(text2.length() - y - 1))
                            : "No diagonal.  Can't happen. (diff_path2)";
                    if (last_op == Operation.EQUAL) {
                        path.getLast().text += text1.charAt(text1.length() - x - 1);
                    } else {
                        path.addLast(new Diff(Operation.EQUAL,
                                text1.substring(text1.length() - x - 1, text1.length() - x)));
                    }
                    last_op = Operation.EQUAL;
                }
            }
        }
        return path;
    }


    /**
     * Trim off common prefix
     * @param text1 First string
     * @param text2 Second string
     * @return The number of characters common to the start of each string.
     */
    public int diff_commonPrefix(String text1, String text2) {
        int pointermin = 0;
        int pointermax = Math.min(text1.length(), text2.length());
        int pointermid = pointermax;
        int pointerstart = 0;
        while (pointermin < pointermid) {
            if (text1.regionMatches(0, text2, 0, pointermid)) {
                pointermin = pointermid;
                pointerstart = pointermin;
            } else {
                pointermax = pointermid;
            }
            pointermid = (pointermax - pointermin) / 2 + pointermin;
        }
        return pointermid;
    }


    /**
     * Trim off common suffix
     * @param text1 First string
     * @param text2 Second string
     * @return The number of characters common to the end of each string.
     */
    public int diff_commonSuffix(String text1, String text2) {
        int pointermin = 0;
        int pointermax = Math.min(text1.length(), text2.length());
        int pointermid = pointermax;
        int pointerend = 0;
        while (pointermin < pointermid) {
            if (text1.regionMatches(text1.length() - pointermid, text2,
                    text2.length() - pointermid, pointermid)) {
                pointermin = pointermid;
                pointerend = pointermin;
            } else {
                pointermax = pointermid;
            }
            pointermid = (pointermax - pointermin) / 2 + pointermin;
        }
        return pointermid;
    }


    /**
     * Do the two texts share a substring which is at least half the length of the
     * longer text?
     * @param text1 First string
     * @param text2 Second string
     * @return Five element String array, containing the prefix of text1, the
     *     suffix of text1, the prefix of text2, the suffix of text2 and the
     *     common middle.  Or null if there was no match.
     */
    protected String[] diff_halfMatch(String text1, String text2) {
        String longtext = text1.length() > text2.length() ? text1 : text2;
        String shorttext = text1.length() > text2.length() ? text2 : text1;
        if (longtext.length() < 10 || shorttext.length() < 1) {
            return null;  // Pointless.
        }

        // First check if the second quarter is the seed for a half-match.
        String[] hm1 = diff_halfMatchI(longtext, shorttext,
                (int) Math.ceil(longtext.length() / 4));
        // Check again based on the third quarter.
        String[] hm2 = diff_halfMatchI(longtext, shorttext,
                (int) Math.ceil(longtext.length() / 2));
        String[] hm;
        if (hm1 == null && hm2 == null) {
            return null;
        } else if (hm2 == null) {
            hm = hm1;
        } else if (hm1 == null) {
            hm = hm2;
        } else {
            // Both matched.  Select the longest.
            hm = hm1[4].length() > hm2[4].length() ? hm1 : hm2;
        }

        // A half-match was found, sort out the return data.
        if (text1.length() > text2.length()) {
            return hm;
            //return new String[]{hm[0], hm[1], hm[2], hm[3], hm[4]};
        } else {
            return new String[]{hm[2], hm[3], hm[0], hm[1], hm[4]};
        }
    }


    /**
     * Does a substring of shorttext exist within longtext such that the substring
     * is at least half the length of longtext?
     * @param longtext Longer string
     * @param shorttext Shorter string
     * @param i Start index of quarter length substring within longtext
     * @return Five element String array, containing the prefix of longtext, the
     *     suffix of longtext, the prefix of shorttext, the suffix of shorttext
     *     and the common middle.  Or null if there was no match.
     */
    private String[] diff_halfMatchI(String longtext, String shorttext, int i) {
        // Start with a 1/4 length substring at position i as a seed.
        String seed = longtext.substring(i,
                i + (int) Math.floor(longtext.length() / 4));
        int j = -1;
        String best_common = "";
        String best_longtext_a = "", best_longtext_b = "";
        String best_shorttext_a = "", best_shorttext_b = "";
        while ((j = shorttext.indexOf(seed, j + 1)) != -1) {
            int prefixLength = diff_commonPrefix(longtext.substring(i),
                    shorttext.substring(j));
            int suffixLength = diff_commonSuffix(longtext.substring(0, i),
                    shorttext.substring(0, j));
            if (best_common.length() < suffixLength + prefixLength) {
                best_common = shorttext.substring(j - suffixLength, j)
                        + shorttext.substring(j, j + prefixLength);
                best_longtext_a = longtext.substring(0, i - suffixLength);
                best_longtext_b = longtext.substring(i + prefixLength);
                best_shorttext_a = shorttext.substring(0, j - suffixLength);
                best_shorttext_b = shorttext.substring(j + prefixLength);
            }
        }
        if (best_common.length() >= longtext.length() / 2) {
            return new String[]{best_longtext_a, best_longtext_b,
                    best_shorttext_a, best_shorttext_b, best_common};
        } else {
            return null;
        }
    }


    /**
     * Reduce the number of edits by eliminating semantically trivial equalities.
     * @param diffs LinkedList of Diff objects
     */
    public void diff_cleanupSemantic(LinkedList<Diff> diffs) {
        if (diffs.isEmpty()) {
            return;
        }
        boolean changes = false;
        Stack<Diff> equalities = new Stack<Diff>();  // Stack of qualities.
        String lastequality = null;  // Always equal to equalities.lastElement().text
        ListIterator<Diff> pointer = diffs.listIterator();
        // Number of characters that changed prior to the equality.
        int length_changes1 = 0;
        // Number of characters that changed after the equality.
        int length_changes2 = 0;
        Diff thisDiff = pointer.next();
        while (thisDiff != null) {
            if (thisDiff.operation == Operation.EQUAL) {
                // equality found
                equalities.push(thisDiff);
                length_changes1 = length_changes2;
                length_changes2 = 0;
                lastequality = thisDiff.text;
            } else {
                // an insertion or deletion
                length_changes2 += thisDiff.text.length();
                if (lastequality != null && (lastequality.length() <= length_changes1)
                        && (lastequality.length() <= length_changes2)) {
                    //System.out.println("Splitting: '" + lastequality + "'");
                    // Walk back to offending equality.
                    while (thisDiff != equalities.lastElement()) {
                        thisDiff = pointer.previous();
                    }
                    pointer.next();

                    // Replace equality with a delete.
                    pointer.set(new Diff(Operation.DELETE, lastequality));
                    // Insert a coresponding an insert.
                    pointer.add(new Diff(Operation.INSERT, lastequality));

                    equalities.pop();  // Throw away the equality we just deleted.
                    if (!equalities.empty()) {
                        // Throw away the previous equality (it needs to be reevaluated).
                        equalities.pop();
                    }
                    if (equalities.empty()) {
                        // There are no previous equalities, walk back to the start.
                        while (pointer.hasPrevious()) {
                            pointer.previous();
                        }
                    } else {
                        // There is a safe equality we can fall back to.
                        thisDiff = equalities.lastElement();
                        while (thisDiff != pointer.previous()) {
                            // Intentionally empty loop.
                        }
                    }

                    length_changes1 = 0;  // Reset the counters.
                    length_changes2 = 0;
                    lastequality = null;
                    changes = true;
                }
            }
            thisDiff = pointer.hasNext() ? pointer.next() : null;
        }

        if (changes) {
            diff_cleanupMerge(diffs);
        }
    }


    /**
     * Reduce the number of edits by eliminating operationally trivial equalities.
     * @param diffs LinkedList of Diff objects
     */
    public void diff_cleanupEfficiency(LinkedList<Diff> diffs) {
        // Reduce the number of edits by eliminating operationally trivial
        // equalities.
        if (diffs.isEmpty()) {
            return;
        }
        boolean changes = false;
        Stack<Diff> equalities = new Stack<Diff>();  // Stack of equalities.
        String lastequality = null;  // Always equal to equalities.lastElement().text
        ListIterator<Diff> pointer = diffs.listIterator();
        // Is there an insertion operation before the last equality.
        boolean pre_ins = false;
        // Is there a deletion operation before the last equality.
        boolean pre_del = false;
        // Is there an insertion operation after the last equality.
        boolean post_ins = false;
        // Is there a deletion operation after the last equality.
        boolean post_del = false;
        Diff thisDiff = pointer.next();
        Diff safeDiff = thisDiff;  // The last Diff that is known to be unsplitable.
        while (thisDiff != null) {
            if (thisDiff.operation == Operation.EQUAL) {
                // equality found
                if (thisDiff.text.length() < Diff_EditCost && (post_ins || post_del)) {
                    // Candidate found.
                    equalities.push(thisDiff);
                    pre_ins = post_ins;
                    pre_del = post_del;
                    lastequality = thisDiff.text;
                } else {
                    // Not a candidate, and can never become one.
                    equalities.clear();
                    lastequality = null;
                    safeDiff = thisDiff;
                }
                post_ins = post_del = false;
            } else {
                // an insertion or deletion
                if (thisDiff.operation == Operation.DELETE) {
                    post_del = true;
                } else {
                    post_ins = true;
                }
        /*
         * Five types to be split:
         * <ins>A</ins><del>B</del>XY<ins>C</ins><del>D</del>
         * <ins>A</ins>X<ins>C</ins><del>D</del>
         * <ins>A</ins><del>B</del>X<ins>C</ins>
         * <ins>A</del>X<ins>C</ins><del>D</del>
         * <ins>A</ins><del>B</del>X<del>C</del>
         */
                if (lastequality != null
                        && ((pre_ins && pre_del && post_ins && post_del)
                        || ((lastequality.length() < Diff_EditCost / 2)
                        && ((pre_ins ? 1 : 0) + (pre_del ? 1 : 0)
                        + (post_ins ? 1 : 0) + (post_del ? 1 : 0)) == 3))) {
                    //System.out.println("Splitting: '" + lastequality + "'");
                    // Walk back to offending equality.
                    while (thisDiff != equalities.lastElement()) {
                        thisDiff = pointer.previous();
                    }
                    pointer.next();

                    // Replace equality with a delete.
                    pointer.set(new Diff(Operation.DELETE, lastequality));
                    // Insert a coresponding an insert.
                    pointer.add(thisDiff = new Diff(Operation.INSERT, lastequality));

                    equalities.pop();  // Throw away the equality we just deleted.
                    lastequality = null;
                    if (pre_ins && pre_del) {
                        // No changes made which could affect previous entry, keep going.
                        post_ins = post_del = true;
                        equalities.clear();
                        safeDiff = thisDiff;
                    } else {
                        if (!equalities.empty()) {
                            // Throw away the previous equality (it needs to be reevaluated).
                            equalities.pop();
                        }
                        if (equalities.empty()) {
                            // There are no previous questionable equalities,
                            // walk back to the last known safe diff.
                            thisDiff = safeDiff;
                        } else {
                            // There is an equality we can fall back to.
                            thisDiff = equalities.lastElement();
                        }
                        while (thisDiff != pointer.previous()) {
                            // Intentionally empty loop.
                        }
                        post_ins = post_del = false;
                    }

                    changes = true;
                }
            }
            thisDiff = pointer.hasNext() ? pointer.next() : null;
        }

        if (changes) {
            diff_cleanupMerge(diffs);
        }
    }


    /**
     * Reorder and merge like edit sections.  Merge equalities.
     * Any edit section can move as long as it doesn't cross an equality.
     * @param diffs LinkedList of Diff objects
     */
    public void diff_cleanupMerge(LinkedList<Diff> diffs) {
        diffs.add(new Diff(Operation.EQUAL, ""));  // Add a dummy entry at the end.
        ListIterator<Diff> pointer = diffs.listIterator();
        int count_delete = 0;
        int count_insert = 0;
        String text_delete = "";
        String text_insert = "";
        Diff thisDiff = pointer.next();
        Diff prevEqual = null;
        int commonlength;
        while (thisDiff != null) {
            // System.out.println(diff);
            if (thisDiff.operation == Operation.INSERT) {
                count_insert++;
                text_insert += thisDiff.text;
                prevEqual = null;
            } else if (thisDiff.operation == Operation.DELETE) {
                count_delete++;
                text_delete += thisDiff.text;
                prevEqual = null;
            } else if (thisDiff.operation == Operation.EQUAL) {
                if (count_delete != 0 || count_insert != 0) {
                    // Delete the offending records.
                    pointer.previous();  // Reverse direction.
                    while (count_delete-- > 0) {
                        pointer.previous();
                        pointer.remove();
                    }
                    while (count_insert-- > 0) {
                        pointer.previous();
                        pointer.remove();
                    }
                    if (count_delete != 0 && count_insert != 0) {
                        // Factor out any common prefixies.
                        commonlength = diff_commonPrefix(text_insert, text_delete);
                        if (commonlength != 0) {
                            if (pointer.hasPrevious()) {
                                thisDiff = pointer.previous();
                                assert thisDiff.operation == Operation.EQUAL
                                        : "Previous diff should have been an equality.";
                                thisDiff.text += text_insert.substring(0, commonlength);
                                pointer.next();
                            } else {
                                pointer.add(new Diff(Operation.EQUAL,
                                        text_insert.substring(0, commonlength)));
                            }
                            text_insert = text_insert.substring(commonlength);
                            text_delete = text_delete.substring(commonlength);
                        }
                        // Factor out any common suffixies.
                        commonlength = diff_commonSuffix(text_insert, text_delete);
                        if (commonlength != 0) {
                            thisDiff = pointer.next();
                            thisDiff.text = text_insert.substring(text_insert.length()
                                    - commonlength) + thisDiff.text;
                            text_insert = text_insert.substring(0, text_insert.length()
                                    - commonlength);
                            text_delete = text_delete.substring(0, text_delete.length()
                                    - commonlength);
                            pointer.previous();
                        }
                    }
                    // Insert the merged records.
                    if (text_delete.length() != 0) {
                        pointer.add(new Diff(Operation.DELETE, text_delete));
                    }
                    if (text_insert.length() != 0) {
                        pointer.add(new Diff(Operation.INSERT, text_insert));
                    }
                    // Step forward to the equality.
                    thisDiff = pointer.hasNext() ? pointer.next() : null;
                } else if (prevEqual != null) {
                    // Merge this equality with the previous one.
                    prevEqual.text += thisDiff.text;
                    pointer.remove();
                    thisDiff = pointer.previous();
                    pointer.next();  // Forward direction
                }
                count_insert = 0;
                count_delete = 0;
                text_delete = "";
                text_insert = "";
                prevEqual = thisDiff;
            } else {
                assert false : thisDiff.operation;  // Can't happen.
            }
            thisDiff = pointer.hasNext() ? pointer.next() : null;
        }
        // System.out.println(diff);
        if (diffs.getLast().text.equals("")) {
            diffs.removeLast();  // Remove the dummy entry at the end.
        }
    }


    /**
     * Add an index to each Diff, represents where the Diff is located in text2.
     * e.g. [(DELETE, "h", 0), (INSERT, "c", 0), (EQUAL, "at", 1)]
     * @param diffs LinkedList of Diff objects
     */
    public void diff_addIndex(LinkedList<Diff> diffs) {
        int i = 0;
        for (Diff aDiff : diffs) {
            aDiff.index = i;
            if (aDiff.operation != Operation.DELETE) {
                i += aDiff.text.length();
            }
        }
    }


    /**
     * loc is a location in text1, compute and return the equivalent location in
     * text2.
     * e.g. "The cat" vs "The big cat", 1->1, 5->8
     * @param diffs LinkedList of Diff objects
     * @param loc Location within text1
     * @return Location within text2
     */
    public int diff_xIndex(LinkedList<Diff> diffs, int loc) {
        int chars1 = 0;
        int chars2 = 0;
        int last_chars1 = 0;
        int last_chars2 = 0;
        Diff lastDiff = null;
        for (Diff aDiff : diffs) {
            if (aDiff.operation != Operation.INSERT) {
                // Equality or deletion.
                chars1 += aDiff.text.length();
            }
            if (aDiff.operation != Operation.DELETE) {
                // Equality or insertion.
                chars2 += aDiff.text.length();
            }
            if (chars1 > loc) {
                // Overshot the location.
                lastDiff = aDiff;
                break;
            }
            last_chars1 = chars1;
            last_chars2 = chars2;
        }
        if (lastDiff != null && lastDiff.operation == Operation.DELETE) {
            // The location was deleted.
            return last_chars2;
        }
        // Add the remaining character length.
        return last_chars2 + (loc - last_chars1);
    }


    /**
     * Convert a Diff list into a pretty HTML report.
     * @param diffs LinkedList of Diff objects
     * @return HTML representation
     */
    public String diff_prettyHtml(LinkedList<Diff> diffs) {
        diff_addIndex(diffs);
        StringBuilder html = new StringBuilder();
        String text;
        for (Diff aDiff : diffs) {
            text = aDiff.text;
            text = text.replaceAll("&", "&amp;").replaceAll("<", "&lt;")
                    .replaceAll(">", "&gt;").replaceAll("\n", "&para;<BR>");
            if (aDiff.operation == Operation.DELETE) {
                html.append("<DEL STYLE=\"background:#FFE6E6;\" TITLE=\"i=").append(aDiff.index).append("\">").append(text).append("</DEL>");
            } else if (aDiff.operation == Operation.INSERT) {
                html.append("<INS STYLE=\"background:#E6FFE6;\" TITLE=\"i=").append(aDiff.index).append("\">").append(text).append("</INS>");
            } else {
                html.append("<SPAN TITLE=\"i=").append(aDiff.index).append("\">").append(text).append("</SPAN>");
            }
        }
        return html.toString();
    }


    //  MATCH FUNCTIONS


    /**
     * Locate the best instance of 'pattern' in 'text' near 'loc'.
     * Returns -1 if no match found.
     * @param text The text to search
     * @param pattern The pattern to search for
     * @param loc The location to search around
     * @return Best match index or -1
     */
    public int match_main(String text, String pattern, int loc) {
        loc = Math.max(0, Math.min(loc, text.length() - pattern.length()));
        if (text.equals(pattern)) {
            // Shortcut (potentially not guaranteed by the algorithm)
            return 0;
        } else if (text.length() == 0) {
            // Nothing to match.
            return -1;
        } else if (text.substring(loc, loc + pattern.length()).equals(pattern)) {
            // Perfect match at the perfect spot!  (Includes case of null pattern)
            return loc;
        } else {
            // Do a fuzzy compare.
            return match_bitap(text, pattern, loc);
        }
    }


    /**
     * Locate the best instance of 'pattern' in 'text' near 'loc' using the
     * Bitap algorithm.  Returns -1 if no match found.
     * @param text The text to search
     * @param pattern The pattern to search for
     * @param loc The location to search around
     * @return Best match index or -1
     */
    public int match_bitap(String text, String pattern, int loc) {
        assert (Match_MaxBits == 0 || pattern.length() <= Match_MaxBits)
                : "Pattern too long for this application.";

        // Initialise the alphabet.
        Map<Character, Integer> s = match_alphabet(pattern);

        int score_text_length = text.length();
        // Coerce the text length between reasonable maximums and minimums.
        score_text_length = Math.max(score_text_length, Match_MinLength);
        score_text_length = Math.min(score_text_length, Match_MaxLength);

        // Highest score beyond which we give up.
        double score_threshold = Match_Threshold;
        // Is there a nearby exact match? (speedup)
        int best_loc = text.indexOf(pattern, loc);
        if (best_loc != -1) {
            score_threshold = Math.min(match_bitapScore(0, best_loc, loc,
                    score_text_length, pattern), score_threshold);
        }
        // What about in the other direction? (speedup)
        best_loc = text.lastIndexOf(pattern, loc + pattern.length());
        if (best_loc != -1) {
            score_threshold = Math.min(match_bitapScore(0, best_loc, loc,
                    score_text_length, pattern), score_threshold);
        }

        // Initialise the bit arrays.
        int matchmask = 1 << (pattern.length() - 1);
        best_loc = -1;

        int bin_min, bin_mid;
        int bin_max = Math.max(loc + loc, text.length());
        // Empty initialization added to appease Java compiler.
        int[] last_rd = new int[0];
        for (int d = 0; d < pattern.length(); d++) {
            // Scan for the best match; each iteration allows for one more error.
            int[] rd = new int[text.length()];

            // Run a binary search to determine how far from 'loc' we can stray at
            // this error level.
            bin_min = loc;
            bin_mid = bin_max;
            while (bin_min < bin_mid) {
                if (match_bitapScore(d, bin_mid, loc, score_text_length, pattern)
                        < score_threshold) {
                    bin_min = bin_mid;
                } else {
                    bin_max = bin_mid;
                }
                bin_mid = (bin_max - bin_min) / 2 + bin_min;
            }
            // Use the result from this iteration as the maximum for the next.
            bin_max = bin_mid;
            int start = Math.max(0, loc - (bin_mid - loc) - 1);
            int finish = Math.min(text.length() - 1, pattern.length() + bin_mid);

            if (text.charAt(finish) == pattern.charAt(pattern.length() - 1)) {
                rd[finish] = (1 << (d + 1)) - 1;
            } else {
                rd[finish] = (1 << d) - 1;
            }
            for (int j = finish - 1; j >= start; j--) {
                if (d == 0) {
                    // First pass: exact match.
                    rd[j] = ((rd[j + 1] << 1) | 1) & (s.containsKey(text.charAt(j))
                            ? s.get(text.charAt(j))
                            : 0);
                } else {
                    // Subsequent passes: fuzzy match.
                    rd[j] = ((rd[j + 1] << 1) | 1) & (s.containsKey(text.charAt(j))
                            ? s.get(text.charAt(j)) : 0) | ((last_rd[j + 1] << 1) | 1)
                            | ((last_rd[j] << 1) | 1) | last_rd[j + 1];
                }
                if ((rd[j] & matchmask) != 0) {
                    double score = match_bitapScore(d, j, loc, score_text_length,
                            pattern);
                    // This match will almost certainly be better than any existing
                    // match.  But check anyway.
                    if (score <= score_threshold) {
                        // Told you so.
                        score_threshold = score;
                        best_loc = j;
                        if (j > loc) {
                            // When passing loc, don't exceed our current distance from loc.
                            start = Math.max(0, loc - (j - loc));
                        } else {
                            // Already passed loc, downhill from here on in.
                            break;
                        }
                    }
                }
            }
            if (match_bitapScore(d + 1, loc, loc, score_text_length, pattern)
                    > score_threshold) {
                // No hope for a (better) match at greater error levels.
                break;
            }
            last_rd = rd;
        }
        return best_loc;
    }


    /**
     * Compute and return the score for a match with e errors and x location.
     * @param e Number of errors in match
     * @param x Location of match
     * @param loc Expected location of match
     * @param score_text_length Coerced version of text's length
     * @param pattern Pattern being sought
     * @return Overall score for match
     */
    private double match_bitapScore(int e, int x, int loc,
                                    int score_text_length, String pattern) {
        int d = Math.abs(loc - x);
        return (e / (float) pattern.length() / Match_Balance)
                + (d / (float) score_text_length / (1.0 - Match_Balance));
    }


    /**
     * Initialise the alphabet for the Bitap algorithm.
     * @param pattern The text to encode
     * @return Hash of character locations
     */
    protected Map<Character, Integer> match_alphabet(String pattern) {
        Map<Character, Integer> s = new HashMap<Character, Integer>();
        char[] char_pattern = pattern.toCharArray();
        for (char c : char_pattern) {
            s.put(c, 0);
        }
        int i = 0;
        for (char c : char_pattern) {
            s.put(c, s.get(c) | (1 << (pattern.length() - i - 1)));
            i++;
        }
        return s;
    }


    //  PATCH FUNCTIONS


    /**
     * Increase the context until it is unique,
     * but don't let the pattern expand beyond Match_MaxBits.
     * @param patch The patch to grow
     * @param text Source text
     */
    public void patch_addContext(Patch patch, String text) {
        String pattern = text.substring(patch.start2, patch.start2 + patch.length1);
        int padding = 0;
        // Increase the context until we're unique (but don't let the pattern
        // expand beyond Match_MaxBits).
        while (text.indexOf(pattern) != text.lastIndexOf(pattern)
                && pattern.length() < Match_MaxBits - Patch_Margin - Patch_Margin) {
            padding += Patch_Margin;
            pattern = text.substring(Math.max(0, patch.start2 - padding),
                    Math.min(text.length(), patch.start2 + patch.length1 + padding));
        }
        // Add one chunk for good luck.
        padding += Patch_Margin;
        // Add the prefix.
        String prefix = text.substring(Math.max(0, patch.start2 - padding),
                patch.start2);
        if (!prefix.equals("")) {
            patch.diffs.addFirst(new Diff(Operation.EQUAL, prefix));
        }
        // Add the suffix.
        String suffix = text.substring(patch.start2 + patch.length1,
                Math.min(text.length(), patch.start2 + patch.length1 + padding));
        if (!suffix.equals("")) {
            patch.diffs.addLast(new Diff(Operation.EQUAL, suffix));
        }

        // Roll back the start points.
        patch.start1 -= prefix.length();
        patch.start2 -= prefix.length();
        // Extend the lengths.
        patch.length1 += prefix.length() + suffix.length();
        patch.length2 += prefix.length() + suffix.length();
    }


    /**
     * Compute a list of patches to turn text1 into text2.
     * A set of diffs will be computed.
     * @param text1 Old text
     * @param text2 New text
     * @return LinkedList of Patch objects.
     */
    public LinkedList<Patch> patch_make(String text1, String text2) {
        // No diffs provided, compute our own.
        LinkedList<Diff> diffs = diff_main(text1, text2, true);
        if (diffs.size() > 2) {
            diff_cleanupSemantic(diffs);
            diff_cleanupEfficiency(diffs);
        }
        return patch_make(text1, text2, diffs);
    }


    /**
     * Compute a list of patches to turn text1 into text2.
     * Use the diffs provided.
     * @param text1 Old text
     * @param text2 New text
     * @param diffs Optional array of diff tuples for text1 to text2.
     * @return LinkedList of Patch objects.
     */
    public LinkedList<Patch> patch_make(String text1, String text2,
                                        LinkedList<Diff> diffs) {
        LinkedList<Patch> patches = new LinkedList<Patch>();
        if (diffs.size() == 0) {
            return patches;  // Get rid of the null case.
        }
        Patch patch = new Patch();
        int char_count1 = 0;  // Number of characters into the text1 string.
        int char_count2 = 0;  // Number of characters into the text2 string.
        // Recreate the patches to determine context info.
        String prepatch_text = text1;
        String postpatch_text = text1;
        for (Diff aDiff : diffs) {
            if (patch.diffs.isEmpty() && aDiff.operation != Operation.EQUAL) {
                // A new patch starts here.
                patch.start1 = char_count1;
                patch.start2 = char_count2;
            }

            if (aDiff.operation == Operation.INSERT) {
                // Insertion
                patch.diffs.add(aDiff);
                patch.length2 += aDiff.text.length();
                postpatch_text = postpatch_text.substring(0, char_count2)
                        + aDiff.text + postpatch_text.substring(char_count2);
            } else if (aDiff.operation == Operation.DELETE) {
                // Deletion.
                patch.length1 += aDiff.text.length();
                patch.diffs.add(aDiff);
                postpatch_text = postpatch_text.substring(0, char_count2)
                        + postpatch_text.substring(char_count2 + aDiff.text.length());
            } else if (aDiff.operation == Operation.EQUAL
                    && aDiff.text.length() <= 2 * Patch_Margin
                    && !patch.diffs.isEmpty() && aDiff != diffs.getLast()) {
                // Small equality inside a patch.
                patch.diffs.add(aDiff);
                patch.length1 += aDiff.text.length();
                patch.length2 += aDiff.text.length();
            }

            if (aDiff.operation == Operation.EQUAL
                    && aDiff.text.length() >= 2 * Patch_Margin) {
                // Time for a new patch.
                if (!patch.diffs.isEmpty()) {
                    patch_addContext(patch, prepatch_text);
                    patches.add(patch);
                    patch = new Patch();
                    prepatch_text = postpatch_text;
                }
            }

            // Update the current character count.
            if (aDiff.operation != Operation.INSERT) {
                char_count1 += aDiff.text.length();
            }
            if (aDiff.operation != Operation.DELETE) {
                char_count2 += aDiff.text.length();
            }
        }
        // Pick up the leftover patch if not empty.
        if (!patch.diffs.isEmpty()) {
            patch_addContext(patch, prepatch_text);
            patches.add(patch);
        }

        return patches;
    }


    /**
     * Merge a set of patches onto the text.  Return a patched text, as well
     * as an array of true/false values indicating which patches were applied.
     * @param patches Array of patch objects
     * @param text Old text
     * @return Two element Object array, containing the new text and an array of
     *      boolean values
     */
    public Object[] patch_apply(LinkedList<Patch> patches, String text) {
        patch_splitMax(patches);
        boolean[] results = new boolean[patches.size()];
        int delta = 0;
        int expected_loc, start_loc;
        String text1, text2;
        int index1, index2;
        LinkedList<Diff> diffs;
        int x = 0;
        for (Patch aPatch : patches) {
            expected_loc = aPatch.start2 + delta;
            text1 = aPatch.text1();
            start_loc = match_main(text, text1, expected_loc);
            if (start_loc == -1) {
                // No match found.  :(
                results[x] = false;
            } else {
                // Found a match.  :)
                results[x] = true;
                delta = start_loc - expected_loc;
                text2 = text.substring(start_loc, start_loc + text1.length());
                if (text1.equals(text2)) {
                    // Perfect match, just shove the replacement text in.
                    text = text.substring(0, start_loc) + aPatch.text2()
                            + text.substring(start_loc + text1.length());
                } else {
                    // Imperfect match.  Run a diff to get a framework of equivalent
                    // indicies.
                    diffs = diff_main(text1, text2, false);
                    index1 = 0;
                    for (Diff aDiff : aPatch.diffs) {
                        if (aDiff.operation != Operation.EQUAL) {
                            index2 = diff_xIndex(diffs, index1);
                            if (aDiff.operation == Operation.INSERT) {
                                // Insertion
                                text = text.substring(0, start_loc + index2) + aDiff.text
                                        + text.substring(start_loc + index2);
                            } else if (aDiff.operation == Operation.DELETE) {
                                // Deletion
                                text = text.substring(0, start_loc + index2)
                                        + text.substring(start_loc + diff_xIndex(diffs,
                                        index1 + aDiff.text.length()));
                            }
                        }
                        if (aDiff.operation != Operation.DELETE) {
                            index1 += aDiff.text.length();
                        }
                    }
                }
            }
            x++;
        }
        return new Object[]{text, results};
    }


    /**
     * Look through the patches and break up any which are longer than the maximum
     * limit of the match algorithm.
     * @param patches LinkedList of Patch objects.
     */
    public void patch_splitMax(LinkedList<Patch> patches) {
        int patch_size;
        String precontext, postcontext;
        Patch patch;
        int start1, start2;
        boolean empty;
        Operation diff_type;
        String diff_text;
        ListIterator<Patch> pointer = patches.listIterator();
        Patch bigpatch = pointer.hasNext() ? pointer.next() : null;
        while (bigpatch != null) {
            if (bigpatch.length1 <= Match_MaxBits) {
                bigpatch = pointer.hasNext() ? pointer.next() : null;
                continue;
            }
            // Remove the big old patch.
            pointer.remove();
            patch_size = Match_MaxBits;
            start1 = bigpatch.start1;
            start2 = bigpatch.start2;
            precontext = "";
            while (!bigpatch.diffs.isEmpty()) {
                // Create one of several smaller patches.
                patch = new Patch();
                empty = true;
                patch.start1 = start1 - precontext.length();
                patch.start2 = start2 - precontext.length();
                if (precontext.length() != 0) {
                    patch.length1 = patch.length2 = precontext.length();
                    patch.diffs.add(new Diff(Operation.EQUAL, precontext));
                }
                while (!bigpatch.diffs.isEmpty()
                        && patch.length1 < patch_size - Patch_Margin) {
                    diff_type = bigpatch.diffs.getFirst().operation;
                    diff_text = bigpatch.diffs.getFirst().text;
                    if (diff_type == Operation.INSERT) {
                        // Insertions are harmless.
                        patch.length2 += diff_text.length();
                        start2 += diff_text.length();
                        patch.diffs.addLast(bigpatch.diffs.removeFirst());
                        empty = false;
                    } else {
                        // Deletion or equality.  Only take as much as we can stomach.
                        diff_text = diff_text.substring(0, Math.min(diff_text.length(),
                                patch_size - patch.length1 - Patch_Margin));
                        patch.length1 += diff_text.length();
                        start1 += diff_text.length();
                        if (diff_type == Operation.EQUAL) {
                            patch.length2 += diff_text.length();
                            start2 += diff_text.length();
                        } else {
                            empty = false;
                        }
                        patch.diffs.add(new Diff(diff_type, diff_text));
                        if (diff_text.equals(bigpatch.diffs.getFirst().text)) {
                            bigpatch.diffs.removeFirst();
                        } else {
                            bigpatch.diffs.getFirst().text = bigpatch.diffs.getFirst().text
                                    .substring(diff_text.length());
                        }
                    }
                }
                // Compute the head context for the next patch.
                precontext = patch.text2();
                precontext = precontext.substring(precontext.length() - Patch_Margin);
                // Append the end context for this patch.
                if (bigpatch.text1().length() > Patch_Margin) {
                    postcontext = bigpatch.text1().substring(0, Patch_Margin);
                } else {
                    postcontext = bigpatch.text1();
                }
                if (postcontext.length() != 0) {
                    patch.length1 += postcontext.length();
                    patch.length2 += postcontext.length();
                    if (!patch.diffs.isEmpty()
                            && patch.diffs.getLast().operation == Operation.EQUAL) {
                        patch.diffs.getLast().text += postcontext;
                    } else {
                        patch.diffs.add(new Diff(Operation.EQUAL, postcontext));
                    }
                }
                if (!empty) {
                    pointer.add(patch);
                }
            }
            bigpatch = pointer.hasNext() ? pointer.next() : null;
        }
    }


    /**
     * Take a list of patches and return a textual representation.
     * @param patches List of Patch objects.
     * @return Text representation of patches.
     */
    public String patch_toText(List<Patch> patches) {
        StringBuilder text = new StringBuilder();
        for (Patch aPatch : patches) {
            text.append(aPatch);
        }
        return text.toString();
    }


    /**
     * Parse a textual representation of patches and return a List of Patch
     * objects.
     * @param textline Text representation of patches
     * @return List of Patch objects
     */
    public List<Patch> patch_fromText(String textline) {
        List<Patch> patches = new LinkedList<Patch>();
        List<String> textList = Arrays.asList(textline.split("\n"));
        LinkedList<String> text = new LinkedList<String>(textList);
        Patch patch;
        Pattern patchHeader
                = Pattern.compile("^@@ -(\\d+),?(\\d*) \\+(\\d+),?(\\d*) @@$");
        Matcher m;
        char sign;
        String line;
        while (!text.isEmpty()) {
            m = patchHeader.matcher(text.getFirst());
            m.matches();
            assert m.groupCount() == 4
                    : "Invalid patch string:\n" + text.getFirst();
            patch = new Patch();
            patches.add(patch);
            patch.start1 = Integer.parseInt(m.group(1));
            if (m.group(2).equals("")) {
                patch.start1--;
                patch.length1 = 1;
            } else if (m.group(2).equals("0")) {
                patch.length1 = 0;
            } else {
                patch.start1--;
                patch.length1 = Integer.parseInt(m.group(2));
            }

            patch.start2 = Integer.parseInt(m.group(3));
            if (m.group(4).equals("")) {
                patch.start2--;
                patch.length2 = 1;
            } else if (m.group(4).equals("0")) {
                patch.length2 = 0;
            } else {
                patch.start2--;
                patch.length2 = Integer.parseInt(m.group(4));
            }
            text.removeFirst();

            while (!text.isEmpty()) {
                try {
                    sign = text.getFirst().charAt(0);
                } catch (IndexOutOfBoundsException e) {
                    // Blank line?  Whatever.
                    text.removeFirst();
                    continue;
                }
                line = text.getFirst().substring(1);
                line = line.replace("+", "%2B");  // decode would change all "+" to " "
                try {
                    line = URLDecoder.decode(line, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // This is a system which does not support UTF-8.  (Not likely)
                    System.out.println("MatchError in Patch.toString: " + e);
                    line = "";
                }
                if (sign == '-') {
                    // Deletion.
                    patch.diffs.add(new Diff(Operation.DELETE, line));
                } else if (sign == '+') {
                    // Insertion.
                    patch.diffs.add(new Diff(Operation.INSERT, line));
                } else if (sign == ' ') {
                    // Minor equality.
                    patch.diffs.add(new Diff(Operation.EQUAL, line));
                } else if (sign == '@') {
                    // Start of next patch.
                    break;
                } else {
                    // WTF?
                    assert false : "Invalid patch mode: '" + sign + "'\n" + line;
                }
                text.removeFirst();
            }
        }
        return patches;
    }
}


/**
 * Class representing one diff operation.
 */
class Diff {
    public DiffMatchPatch.Operation operation;
    // One of: INSERT, DELETE or EQUAL.
    public String text;
    // The text associated with this diff operation.
    public int index;
    // Where in the source text does this diff fit?  Not usualy set.

    /**
     * Constructor.  Initializes the diff with the provided values.
     * @param operation One of INSERT, DELETE or EQUAL
     * @param text The text being applied
     */
    public Diff(DiffMatchPatch.Operation operation, String text) {
        // Construct a diff with the specified operation and text.
        this.operation = operation;
        this.text = text;
        this.index = -1;
    }

    /**
     * Display a human-readable version of this Diff.
     * @return text version
     */
    public String toString() {
        String prettyText = this.text.replace('\n', '\u00b6');
        return "Diff(" + this.operation + ",\"" + prettyText + "\")";
    }

    /**
     * Is this Diff equivalent to another Diff?
     * @param d Another Diff to compare against
     * @return true or false
     */
    public boolean equals(Object d) {
        try {
            return (((Diff) d).operation == this.operation)
                    && (((Diff) d).text.equals(this.text));
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     *
     * @return number represending this object's values
     */
    public int hashCode() {
        return this.operation.hashCode() + this.text.hashCode();
    }
}


/**
 * Class representing one patch operation.
 */
class Patch {
    public LinkedList<Diff> diffs;
    public int start1;
    public int start2;
    public int length1;
    public int length2;

    /**
     * Constructor.  Initializes with an empty list of diffs.
     */
    public Patch() {
        this.diffs = new LinkedList<Diff>();
    }

    /**
     * Emmulate GNU diff's format.
     * Header: @@ -382,8 +481,9 @@
     * Indicies are printed as 1-based, not 0-based.
     * @return The GNU diff string
     */
    public String toString() {
        String coords1, coords2;
        if (this.length1 == 0) {
            coords1 = this.start1 + ",0";
        } else if (this.length1 == 1) {
            coords1 = Integer.toString(this.start1 + 1);
        } else {
            coords1 = (this.start1 + 1) + "," + this.length1;
        }
        if (this.length2 == 0) {
            coords2 = this.start2 + ",0";
        } else if (this.length2 == 1) {
            coords2 = Integer.toString(this.start2 + 1);
        } else {
            coords2 = (this.start2 + 1) + "," + this.length2;
        }
        StringBuilder txt = new StringBuilder("@@ -" + coords1 + " +" + coords2 + " @@\n");
        // Escape the body of the patch with %xx notation.
        for (Diff aDiff : this.diffs) {
            switch(aDiff.operation) {
                case DELETE:
                    txt.append("-");
                    break;
                case EQUAL:
                    txt.append(" ");
                    break;
                case INSERT:
                    txt.append("+");
                    break;
                default:
                    assert false : "Invalid diff operation in patch_obj.toString()";
            }
            try {
                txt.append(URLEncoder.encode(aDiff.text, "UTF-8").replace('+', ' ')).append("\n");
            } catch (UnsupportedEncodingException e) {
                // This is a system which does not support UTF-8.  (Not likely)
                System.out.println("MatchError in Patch.toString: " + e);
                return "";
            }
        }
        // Replicate the JavaScript encodeURI() function (not including %20)
        txt = new StringBuilder(txt.toString().replace("%3D", "=").replace("%3B", ";").replace("%27", "'")
                .replace("%2C", ",").replace("%2F", "/").replace("%7E", "~")
                .replace("%21", "!").replace("%40", "@").replace("%23", "#")
                .replace("%24", "$").replace("%26", "&").replace("%28", "(")
                .replace("%29", ")").replace("%2B", "+").replace("%3A", ":")
                .replace("%3F", "?"));
        return txt.toString();
    }

    /**
     * Compute and return the source text (all equalities and deletions).
     * @return Source text
     */
    public String text1() {
        StringBuilder txt = new StringBuilder();
        for (Diff aDiff : this.diffs) {
            if (aDiff.operation != DiffMatchPatch.Operation.INSERT) {
                txt.append(aDiff.text);
            }
        }
        return txt.toString();
    }

    /**
     * Compute and return the destination text (all equalities and insertions).
     * @return Destination text
     */
    public String text2() {
        StringBuilder txt = new StringBuilder();
        for (Diff aDiff : this.diffs) {
            if (aDiff.operation != DiffMatchPatch.Operation.DELETE) {
                txt.append(aDiff.text);
            }
        }
        return txt.toString();
    }
}
