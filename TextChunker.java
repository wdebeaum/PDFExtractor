package TRIPS.PDFExtractor;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.pdmodel.font.PDFont;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/** Replacement for Tabula's TextElement.mergeWords() function, which takes a
 * collection of single-glyph TextElements and returns TextChunks corresponding
 * to lines of text. Instead of building up chunks incrementally and depending
 * on font size (or individual glyph dimensions), we consider the whole page
 * statistically, dividing rows and columns (columns first) where there are no
 * or few characters, and for each cell, grouping connected components of
 * Y-overlapping glyphs into lines.
 */
public class TextChunker {
  final static boolean DEBUG = false;
  final static boolean DEBUG_BIN_SIZE = false;
  final static boolean DEBUG_LINES = false;
  /** Maximum count for a histogram bin considered to be a gap, as a proportion
   * of the average histogram bin value.
   */
  final static double MAX_GAP_COUNT_PROPORTION = 0.25; // TODO consider raising this now that we can re-merge specific lines if necessary
  final static double MIN_LINE_HEIGHT = 5; // less than that and text is unreadable
  final static double MAX_LINE_HEIGHT = 50; // obnoxiously large text

  /** Bounds for the whole collection of glyphs' center coordinates. */
  double minX=0, minY=0, maxX=-1, maxY=-1; // start with detectable nonsense
  /** Are we making chunks with different X coordinates (columns), or different Y coordinates (sections/paragraphs)? */
  boolean coordIsX = true;
  /** Map each X or Y coordinate (according to coordIsX) to the list of glyphs centered there. */
  TreeMap<Double,LinkedList<TextElement>> glyphsByCoord =
    new TreeMap<Double,LinkedList<TextElement>>();

  public boolean isEmpty() {
    return minX > maxX;
  }

  public static boolean isBlank(TextElement glyph) {
    return glyph.getText().matches("^\\s*$");
  }

  public void add(TextElement glyph) {
    // we go by the center of the glyph's bounding box (and ignore its
    // dimensions) to minimize the confusion resulting from ascenders and
    // descenders (and to a lesser extent sub- and superscripts)
    double x = glyph.getCenterX(), y = glyph.getCenterY();
    double c = (coordIsX ? x : y);
    // add glyph to glyphsByCoord
    if (glyphsByCoord.containsKey(c)) {
      glyphsByCoord.get(c).add(glyph);
    } else {
      LinkedList<TextElement> l = new LinkedList<TextElement>();
      l.add(glyph);
      glyphsByCoord.put(c, l);
    }
    // update bounds for the whole collection of glyphs
    if (isEmpty()) { // first glyph added
      minX = maxX = x;
      minY = maxY = y;
    } else { // not first glyph
      if (minX > x) minX = x;
      if (maxX < x) maxX = x;
      if (minY > y) minY = y;
      if (maxY < y) maxY = y;
    }
  }

  public void addAll(Collection<TextElement> glyphs) {
    for (TextElement g : glyphs)
      add(g);
  }

  /** Attempt to split a page of text into columns (if coordIsX) or
   * sections/paragraphs (if not), and make a TextChunker for each.
   */
  public List<TextChunker> split() {
    if (DEBUG) System.err.println("Splitting " + (coordIsX ? "X" : "Y") + " in (" + minX + ", " + minY + ")-(" + maxX + ", " + maxY + ")");
    double minCoord = (coordIsX ? minX : minY);
    double maxCoord = (coordIsX ? maxX : maxY);
    double dim = maxCoord - minCoord + 0.5;
    // for each reasonable histogram bin size
    binSizeLoop:
    for (int binSize = (int)MIN_LINE_HEIGHT;// reasonable line heights also
         binSize <= (int)MAX_LINE_HEIGHT && // happen to be reasonable bin sizes
	   binSize <= Math.floor(dim / 3); // at least 3 bins
	 binSize++) {
      // make a histogram of the relevant coordinate of all glyphs
      int binCount = (int)Math.ceil(dim / binSize);
      int[] histogram = new int[binCount];
      for (Map.Entry<Double,LinkedList<TextElement>> entry :
	   glyphsByCoord.entrySet()) {
	double coord = entry.getKey();
	LinkedList<TextElement> glyphs = entry.getValue();
	int bin = (int)Math.floor((coord - minCoord) / binSize);
	// do this, except ignore blank glyphs
	//histogram[bin] += glyphs.size();
	for (TextElement g : glyphs)
	  if (!isBlank(g))
	    histogram[bin]++;
      }
      // find the average count in the histogram
      double avg = 0;
      for (int count : histogram)
	avg += count;
      avg /= binCount;
      // classify bins by whether they seem empty enough to be gaps
      int gapCountThreshold = (int)Math.ceil(avg * MAX_GAP_COUNT_PROPORTION);
      if (DEBUG_BIN_SIZE) System.err.println("binSize=" + binSize + "; binCount=" + binCount + "; gapCountThreshold=" + gapCountThreshold);
      // bit pattern indicating whether previous 3 bins are gap bins
      int prevGaps = 0;
      // index of previous bin that wasn't a gap bin
      int prevNonGap = -1;
      LinkedList<Double> boundaries = new LinkedList<Double>();
      for (int bin = 0; bin < binCount; bin++) {
	boolean isGap = (histogram[bin] < gapCountThreshold);
	// shift isGap into little end of prevGaps, and prevent wrap to negative
	prevGaps = (((prevGaps << 1) | (isGap ? 1 : 0)) & 0x3fffffff);
	if (DEBUG_BIN_SIZE) System.err.println(histogram[bin] + "\t" + Integer.toString(prevGaps, 2));
        // these patterns indicate binSize is too small (1=gap, 0=glyphs)
	if ((prevGaps & 0b1111111) == 0b1010101 || // too many adjacent gaps
	    (prevGaps & 0b11111111) == 0b10101001 || // ...or almost-adj. gaps
	    (prevGaps & 0b11111111) == 0b10100101 ||
	    (prevGaps & 0b11111111) == 0b10010101 ||
	    (prevGaps & 0b111111111) == 0b100100101 ||
	    (prevGaps & 0b111111111) == 0b100101001 ||
	    (prevGaps & 0b111111111) == 0b101001001 ||
	    (prevGaps & 0b111111) == 0b110110 || // too big adjacent gaps
	    (prevGaps & 0b11111111) == 0b11101110 ||
	    (prevGaps & 0b1111111111) == 0b1111011110 ||
	    (prevGaps & 0b111111111111) == 0b111110111110 ||
	    (prevGaps & 0b11111111111111) == 0b11111101111110 ||
	    // extremely big gap not near the ends
	    ((prevGaps & 0b11111111) == 0b11111110 &&
	      !(prevNonGap <= 0 || bin == binCount - 1)
	    ))
	  continue binSizeLoop;
	if (!isGap) {
	  if (prevNonGap < bin - 1) { // found the end of a gap
	    double start = minCoord + (prevNonGap+1) * binSize;
	    double end = minCoord + bin * binSize;
	    boundaries.add((start + end) / 2);
	  }
	  prevNonGap = bin;
	}
      }
      if (DEBUG) System.err.println("old boundaries=" + boundaries);
      // if organizing by Y coordinate, add extra boundaries where the font or
      // font size changes from one line to the next, and there is no vertical
      // overlap between the lines
      if (!coordIsX) {
	ListIterator<Double> boundIter = boundaries.listIterator();
	Double currBound = null;
	double prevMaxY = -Double.MAX_VALUE;
	HashSet<AbstractMap.SimpleEntry<PDFont,Float>> prevFonts = null;
	for (Map.Entry<Double,LinkedList<TextElement>> entry :
	     glyphsByCoord.entrySet()) {
	  // find this line's Y extent and its set of most frequent (font,size)
	  // pairs (abusing AbstractMap.SimpleEntry as a generic Pair type
	  // here)
	  double minY = Double.MAX_VALUE;
	  double maxY = -Double.MAX_VALUE;
	  HashMap<AbstractMap.SimpleEntry<PDFont,Float>,Integer> fontFreqs =
	    new HashMap<AbstractMap.SimpleEntry<PDFont,Float>,Integer>();
	  int maxFontFreq = 0;
	  List<TextElement> glyphs = entry.getValue();
	  for (TextElement g : glyphs) {
	    if (minY > g.getMinY()) minY = g.getMinY();
	    if (maxY < g.getMaxY()) maxY = g.getMaxY();
	    AbstractMap.SimpleEntry<PDFont,Float> key =
	      new AbstractMap.SimpleEntry<PDFont,Float>
	        (g.getFont(), g.getFontSize());
	    if (!fontFreqs.containsKey(key))
	      fontFreqs.put(key, 0);
	    int freq = fontFreqs.get(key) + 1;
	    fontFreqs.put(key, freq);
	    if (maxFontFreq < freq) maxFontFreq = freq;
	  }
	  // get just the set of most frequent (font,size) pairs
	  HashSet<AbstractMap.SimpleEntry<PDFont,Float>> fonts =
	    new HashSet<AbstractMap.SimpleEntry<PDFont,Float>>();
	  for (Map.Entry<AbstractMap.SimpleEntry<PDFont,Float>,Integer> ffEntry
	       : fontFreqs.entrySet()) {
	    if (ffEntry.getValue() == maxFontFreq) {
	      AbstractMap.SimpleEntry<PDFont,Float> fontAndSize =
	        ffEntry.getKey();
	      if (fontAndSize.getValue() == 1.0f) // bogus font size
		// replace with line height
		fontAndSize.setValue((float)(maxY - minY));
	      fonts.add(fontAndSize);
	    }
	  }
	  if (DEBUG) System.err.println(glyphs.size() + " glyphs centered at Y=" + entry.getKey() + " have Y in [" + minY + "," + maxY + "] and most frequent font/size(s): " + fonts);
	  if (minY >= prevMaxY && prevFonts != null && // no overlap, not first
	      !prevFonts.equals(fonts)) { // unequal most frequent fonts
	    if (DEBUG) System.err.println("...and don't overlap with previous Y, and don't have the same most frequent font(s)");
	    // the two lines share no (font,size) pairs.
	    // add a boundary between the two lines.
	    // first find the right spot in boundaries list
	    while (boundIter.hasNext() &&
		   (currBound == null || currBound < prevMaxY))
	      currBound = boundIter.next();
	    if (DEBUG) System.err.println("...nearest existing boundary is " + currBound);
	    if (!(currBound != null &&
		  currBound >= prevMaxY && currBound <= minY)) {
	      // don't already have this boundary, add it on the correct side
	      // of currBound
	      if (currBound != null && currBound > minY) boundIter.previous();
	      double newBound = (prevMaxY + minY) / 2;
	      if (DEBUG) {
		System.err.println("***adding new bound at Y=" + newBound);
		if (boundIter.hasPrevious()) {
		  System.err.println("***  after old bound at Y=" + boundIter.previous());
		  boundIter.next();
		} else {
		  System.err.println("***  at start");
		}
		if (boundIter.hasNext()) {
		  System.err.println("***  before old bound at Y=" + boundIter.next());
		  boundIter.previous();
		} else {
		  System.err.println("***  at end");
		}
	      }
	      boundIter.add(newBound);
	      // make sure we check the new boundary next time
	      boundIter.previous();
	      currBound = boundIter.next();
	    }
	  }
	  prevMaxY = maxY;
	  prevFonts = fonts;
	}
      }
      if (DEBUG) {
	System.err.println("binSize=" + binSize + " is OK!");
	System.err.println("boundaries=" + boundaries);
      }
      // make sub-chunkers
      List<TextChunker> ret = new LinkedList<TextChunker>();
      for (int i = 0; i <= boundaries.size(); i++) {
	TextChunker sub = new TextChunker();
	sub.coordIsX = !coordIsX;
	ret.add(sub);
      }
      // add each glyph to the appropriate sub chunker
      for (Map.Entry<Double,LinkedList<TextElement>> entry :
	   glyphsByCoord.entrySet()) {
	double coord = entry.getKey();
	LinkedList<TextElement> glyphs = entry.getValue();
	// find the first index such that the boundary at that index is greater
	// than the coord for these glyphs (failing that, the last index of ret)
	int subIndex;
	for (subIndex = 0;
	     subIndex < boundaries.size() && coord > boundaries.get(subIndex);
	     subIndex++)
	  ;
	// add these glyphs to that sub chunker
	ret.get(subIndex).addAll(glyphs);
      }
      // remove empty sub-chunkers
      ListIterator<TextChunker> iter = ret.listIterator();
      while (iter.hasNext()) {
	if (iter.next().isEmpty())
	  iter.remove();
      }
      if (DEBUG) System.err.println("returning " + ret.size() + " sub-chunkers");
      return ret;
    }
    // if we got here, we failed to split with any histogram bin size
    // just return one new chunk with all the glyphs and opposite coord
    TextChunker sub = new TextChunker();
    sub.coordIsX = !coordIsX;
    for (Map.Entry<Double,LinkedList<TextElement>> entry :
	 glyphsByCoord.entrySet()) {
      sub.addAll(entry.getValue());
    }
    List<TextChunker> ret = new ArrayList<TextChunker>(1);
    ret.add(sub);
    return ret;
  }

  /** Add a TextElement to left containing a space, if appropriate, given right
   * will be added afterward. Use more or less the same rules as Tabula's
   * TextElement.mergeWords().
   */
  static void maybeAddSpace(TextChunk left, TextElement right) {
    List<TextElement> leftTEs = left.getTextElements();
    if (leftTEs.isEmpty()) return; // don't add space at beginning
    TextElement prevChar = leftTEs.get(leftTEs.size()-1);
    float lastWordSpacing = prevChar.getWidthOfSpace();
    float wordSpacing = right.getWidthOfSpace();
    float deltaSpace = 0;
    if (Float.isNaN(wordSpacing) || wordSpacing == 0) {
      deltaSpace = Float.MAX_VALUE;
    } else if (lastWordSpacing < 0) {
      deltaSpace = wordSpacing * 0.5f;
    } else {
      deltaSpace = ((wordSpacing + lastWordSpacing) / 2.0f) * 0.5f;
    }
    // tabula calculates this as a kind of moving average over the whole chunk,
    // but let's save some time by approximating that as giving equal weight to
    // 3 parts: prevChar, right, and the rest (if any)
    float averageCharWidth;
    if (leftTEs.size() > 1) {
      averageCharWidth = (float)(
	(leftTEs.size() - 1) / (left.getWidth() - prevChar.getWidth()) +
	prevChar.getWidth() +
	right.getWidth()
      ) / 3.0f;
    } else { // prevChar is the *only* one in leftTEs
      averageCharWidth = (float)(prevChar.getWidth() + right.getWidth()) / 2.0f;
    }
    float deltaCharWidth = averageCharWidth * 0.3f;
    float expectedStartOfNextWordX = -Float.MAX_VALUE;
    float endOfLastTextX = prevChar.getRight();
    if (endOfLastTextX != -1)
      expectedStartOfNextWordX =
        endOfLastTextX + Math.min(deltaCharWidth, deltaSpace);
    if (/*!acrossVerticalRuling && we don't use rulings here */
        /*sameLine && we already know it's the same line */
	expectedStartOfNextWordX < right.getLeft() &&
	!prevChar.getText().endsWith(" ")) {
      left.add(
	new TextElement(
	  prevChar.getTop(),
	  prevChar.getLeft(),
	  expectedStartOfNextWordX - prevChar.getLeft(),
	  (float) prevChar.getHeight(),
	  prevChar.getFont(),
	  prevChar.getFontSize(),
	  " ",
	  prevChar.getWidthOfSpace()
	)
      );
    }
  }

  static void maybeAddSpace(TextChunk left, TextChunk right) {
    List<TextElement> rightTEs = right.getTextElements();
    if (!rightTEs.isEmpty())
      maybeAddSpace(left, rightTEs.get(0));
  }

  /** Return a list of TextChunks each representing one line of text, without
   * splitting first.
   */
  public List<TextChunk> chunk() {
    if (DEBUG) System.err.println("chunking region from (" + minX + ", " + minY + ") to (" + maxX + ", " + maxY + ") into lines");
    ArrayList<TextChunk> chunks = new ArrayList<TextChunk>();
    // assign each glyph to the chunk it overlaps with in Y, merging chunks as
    // necessary to make that chunk unique.
    // for each glyph
    for (Map.Entry<Double,LinkedList<TextElement>> entry :
         glyphsByCoord.entrySet()) {
      for (TextElement g : entry.getValue()) {
	// blanks are more trouble than they're worth; instead depend on
	// maybeAddSpace
	if (isBlank(g)) continue;
	// find the first and last chunks it overlaps with in Y
	int first;
	for (first = 0;
	     first < chunks.size() &&
	       chunks.get(first).getMinY() > g.getMaxY();
	     first++)
	  ;
	int last;
	for (last = first;
	     last < chunks.size() &&
	       chunks.get(last).getMaxY() > g.getMinY();
	     last++)
	  ;
	last--; // not one past last, rather last itself
	if (first > last) { // no chunks matched, insert a new one
	  chunks.add(first, new TextChunk(g));
	} else if (first == last) { // exactly 1 chunk matched, add glyph to it
	  chunks.get(first).add(g);
	} else { // more than 1 chunk matched, merge them and add glyph
	  if (DEBUG_LINES) System.err.println("merging lines [" + first + "," + last + "] because they're overlapped by glyph " + g);
	  TextChunk only = chunks.get(last);
	  if (DEBUG_LINES) System.err.println("  last line: " + only);
	  for (int i = first; i < last; i++) {
	    if (DEBUG_LINES) System.err.println("  rest line: " + chunks.get(i));
	    only.merge(chunks.get(i));
	  }
	  only.add(g);
	  chunks.subList(first, last).clear(); // excluding last/only
	  if (DEBUG_LINES) System.err.println("  merged line: " + only);
	}
      }
    }
    // remake each chunk so that its glyphs are in order and spaces are
    // inserted appropriately
    for (int i = 0; i < chunks.size(); i++) {
      List<TextElement> glyphs = chunks.get(i).getTextElements();
      glyphs.sort(new Comparator<TextElement>() {
	@Override public int compare(TextElement a, TextElement b) {
	  return Double.compare(a.getCenterX(), b.getCenterX());
	}
      });
      TextChunk inOrder = new TextChunk(glyphs.get(0));
      for (int j = 1; j < glyphs.size(); j++) {
	TextElement g = glyphs.get(j);
	// TODO skip g if it's the same char as last of chunk, and they overlap more than 50%, or if g is a space and has same top left as last of chunk
	maybeAddSpace(inOrder, g);
	inOrder.add(g);
      }
      chunks.set(i, inOrder);
    }
    if (DEBUG) {
      for (TextChunk c : chunks)
	System.err.println("CHUNK: " + c.getText());
    }
    return chunks;
  }

  /** Attempt to split into columns and then sections/paragraphs, and then
   * chunk each of those, returning all chunks in a TreeMap organized by center
   * Y coordinate.
   */
  TreeMap<Integer,LinkedList<TextChunk>> splitAndChunk() {
    // collect chunks into lists based on their center Y coordinate (divided by
    // MIN_LINE_HEIGHT and rounded), so we can re-merge lines that were
    // erroneously split into different columns
    TreeMap<Integer,LinkedList<TextChunk>> chunksByY =
      new TreeMap<Integer,LinkedList<TextChunk>>();
    // split columns
    for (TextChunker col : split()) {
      // split sections/paragraphs within each column
      for (TextChunker cell : col.split()) {
	// split lines within each section/paragraph
	for (TextChunk c : cell.chunk()) {
	  // round to the nearest MIN_LINE_HEIGHT in order to make it more
	  // likely that adjacent line chunks with slightly different center Y
	  // coordinates end up in the same entry, or at least adjacent entries
	  double y = c.getCenterY();
	  int i = (int)Math.round(y / MIN_LINE_HEIGHT);
	  // add the line to chunksByY
	  if (chunksByY.containsKey(i)) {
	    chunksByY.get(i).add(c);
	  } else {
	    LinkedList<TextChunk> l = new LinkedList<TextChunk>();
	    l.add(c);
	    chunksByY.put(i, l);
	  }
	}
      }
    }
    return chunksByY;
  }

  /** Re-merge horizontally adjacent lines if they're close enough, given a
   * value returned by splitAndChunk().
   */
  static List<TextChunk> remerge(TreeMap<Integer,LinkedList<TextChunk>> chunksByY) {
    // NOTE: we know the list in each map entry is in order of increasing X
    // because of the way it was constructed. We also know it has at least 1
    // element because otherwise the map entry wouldn't exist.
    int estNumChunks = 0;
    for (Map.Entry<Integer,LinkedList<TextChunk>> currEntry :
         chunksByY.entrySet()) {
      int y = currEntry.getKey();
      LinkedList<TextChunk> currChunks = currEntry.getValue();
      ListIterator<TextChunk> currIter = currChunks.listIterator();
      // we also check (vertically) adjacent entries
      ListIterator<TextChunk> prevIter =
        (chunksByY.containsKey(y-1) ? chunksByY.get(y-1).listIterator() : null);
      ListIterator<TextChunk> nextIter =
        (chunksByY.containsKey(y+1) ? chunksByY.get(y+1).listIterator() : null);
      while (currIter.hasNext()) {
	TextChunk left = currIter.next();
	double leftMaxX = left.getMaxX();
	double leftHeight = left.getHeight();
	TextChunk prevRight = null, currRight = null, nextRight = null;
	boolean currGood = false;
	// get currRight by advancing currIter if we can
	if (currIter.hasNext()) {
	  currRight = currIter.next();
	  currGood = true;
	  currIter.previous();
	}
	// advance prev and next iterators and get potential right sides from
	// them as well, if they exist
	if (prevIter != null) {
	  while (prevIter.hasNext()) {
	    TextChunk c = prevIter.next();
	    if (c.getMinX() > leftMaxX - leftHeight) {
	      prevRight = c;
	      prevIter.previous();
	      break;
	    }
	  }
	}
	if (nextIter != null) {
	  while (nextIter.hasNext()) {
	    TextChunk c = nextIter.next();
	    if (c.getMinX() > leftMaxX - leftHeight) {
	      nextRight = c;
	      nextIter.previous();
	      break;
	    }
	  }
	}
	//if (DEBUG) System.err.println("left:      " + left + "\nprevRight: " + prevRight + "\ncurrRight: " + currRight + "\nnextRight: " + nextRight);
	// check current line's right first
	if (currGood) {
	  double currGapWidth = currRight.getMinX() - leftMaxX;
	  double currTwiceMinHeight =
	    2 * Math.min(leftHeight, currRight.getHeight());
	  currGood = (currGapWidth < currTwiceMinHeight); // close enough
	  if (DEBUG && currGood) System.err.println("currGood: gapWidth=" + currGapWidth + "; 2mh=" + currTwiceMinHeight);
	}
	if (currGood) {
	  if (DEBUG) System.err.println("merging line chunks:\n      left : " + left + "\n  currRight: " + currRight);
	  maybeAddSpace(left, currRight);
	  left.merge(currRight); // add right's contents to left
	  currIter.remove(); // remove right
	  currIter.previous(); // go back to left (maybe more rights to merge)
	} else { // current line's right not good
	  // check vertically adjacent lines
	  boolean prevGood = (prevRight != null);
	  boolean nextGood = (nextRight != null);
	  double prevGapWidth = 1000, nextGapWidth = 1000;
	  if (prevGood) {
	    prevGapWidth = prevRight.getMinX() - leftMaxX;
	    double prevTwiceMinHeight =
	      2 * Math.min(leftHeight, prevRight.getHeight());
	    prevGood = (prevGapWidth < prevTwiceMinHeight);
	    if (DEBUG && prevGood) System.err.println("prevGood: gapWidth=" + prevGapWidth + "; 2mh=" + prevTwiceMinHeight);
	  }
	  if (nextGood) {
	    nextGapWidth = nextRight.getMinX() - leftMaxX;
	    double nextTwiceMinHeight =
	      2 * Math.min(leftHeight, nextRight.getHeight());
	    nextGood = (nextGapWidth < nextTwiceMinHeight);
	    if (DEBUG && nextGood) System.err.println("nextGood: gapWidth=" + nextGapWidth + "; 2mh=" + nextTwiceMinHeight);
	  }
	  if (prevGood && nextGood) {
	    if (prevGapWidth == nextGapWidth) {
	      // both rights are good and equally distant from left; decide
	      // based on which has more overlap
	      double prevVerticalOverlap =
		(prevGood ? left.verticalOverlap(prevRight) : 0);
	      double nextVerticalOverlap =
		(nextGood ? left.verticalOverlap(nextRight) : 0);
	      if (DEBUG) System.err.println("prevVerticalOverlap=" + prevVerticalOverlap + "; nextVerticalOverlap=" + nextVerticalOverlap);
	      if (prevVerticalOverlap > nextVerticalOverlap) {
		nextGood = false;
	      } else {
		prevGood = false;
	      }
	    // otherwise take the closer one
	    } else if (prevGapWidth < nextGapWidth) {
	      nextGood = false;
	    } else {
	      prevGood = false;
	    }
	  }
	  // now at most one of prevGood and nextGood is true; merge that one
	  if (prevGood) {
	    if (DEBUG) System.err.println("merging line chunks:\n      left : " + left + "\n  prevRight: " + prevRight);
	    maybeAddSpace(left, prevRight);
	    left.merge(prevRight);
	    prevIter.remove();
	    currIter.previous();
	  } else if (nextGood) {
	    if (DEBUG) System.err.println("merging line chunks:\n      left : " + left + "\n  nextRight: " + nextRight);
	    maybeAddSpace(left, nextRight);
	    left.merge(nextRight);
	    nextIter.remove();
	    currIter.previous();
	  }
	}
      }
      estNumChunks += currChunks.size();
    }
    // add all chunks to a list to return
    List<TextChunk> chunks = new ArrayList<TextChunk>(estNumChunks);
    for (LinkedList<TextChunk> cs : chunksByY.values()) {
      chunks.addAll(cs);
    }
    return chunks;
  }

  public static List<TextChunk> chunk(Collection<TextElement> glyphs) {
    TextChunker tc = new TextChunker();
    tc.addAll(glyphs);
    TreeMap<Integer,LinkedList<TextChunk>> chunksByY = tc.splitAndChunk();
    return remerge(chunksByY);
  }
}
