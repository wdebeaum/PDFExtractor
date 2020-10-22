package TRIPS.PDFExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import technology.tabula.HasText;
import technology.tabula.Line;
import technology.tabula.ObjectExtractor;
//import technology.tabula.Page; conflicts (obvs.)
import technology.tabula.Rectangle;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Ruling;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import TRIPS.KQML.KQMLBadPerformativeException;
import TRIPS.KQML.KQMLList;
import TRIPS.KQML.KQMLObject;
import TRIPS.KQML.KQMLPerformative;
import TRIPS.KQML.KQMLToken;
import TRIPS.util.cwc.Args;
import TRIPS.util.cwc.CWCException;
import TRIPS.util.cwc.InvalidArgument;

/** One page of a PDF {@link Document}. */
public class Page implements HasID, TextMatch.Searchable {
  final static boolean USE_TEXT_CHUNKER = true; // use new TextChunker instead of Tabula's mergeWords (experimental)
  final String id;
  @Override public String getID() { return id; }
  final PDPage pdPage;
  final Document document;
  final int pageIndex;
  List<Region> regions;
  HashSet<Listener> listeners;
  boolean detectedTableRegions; // detected for the whole page, anyway
  boolean detectedParagraphRegions;
  boolean detectedRulingRegions;

  public Page(PDPage pdPage, Document document, int pageIndex) {
    id = HasID.getNextIDAndPut(this);
    this.pdPage = pdPage;
    this.document = document;
    this.pageIndex = pageIndex;
    regions = Collections.synchronizedList(new LinkedList<Region>());
    listeners = new HashSet<Listener>();
    detectedTableRegions = false;
    detectedParagraphRegions = false;
    detectedRulingRegions = false;
  }

  //public PDRectangle getPDBBox() { return pdPage.getBBox(); }
  public PDRectangle getPDBBox() {
    // using the actual .getBBox() bounding box from PDFBox is problematic,
    // because both Tabula and the renderer subtract the min X/Y coordinates,
    // and the renderer uses the crop box instead of the "b" box
    PDRectangle cropBoxWithOffset = pdPage.getCropBox();
    // make a new version of the bounding box with the offset discarded
    return new PDRectangle(
      cropBoxWithOffset.getWidth(),
      cropBoxWithOffset.getHeight()
    );
  }

  public PDPage getPDPage() { return pdPage; }

  public Document getDocument() { return document; }
  
  public int getPageIndex() { return pageIndex; }

  public List<Region> getRegions() { return regions; }

  /** Get the most recently added Region whose resize handle is at (x,y), or
   * null if there is no resize handle there.
   */
  public Region getRegionAt(int x, int y) {
    Region ret = null;
    synchronized (regions) {
      for (Region r : regions) {
	if (r.isHighlighted() && !r.getHandleAt((double)x, (double)y).isEmpty())
	  ret = r;
      }
    }
    return ret;
  }

  /** Get a list of highlighted regions containing pixel (x,y) (not just
   * handles).
   */
  public List<Region> getRegionsAt(int x, int y) {
    List<Region> ret = new LinkedList<Region>();
    synchronized (regions) {
      for (Region r : regions) {
	if (r.isHighlighted() && r.contains(x, y))
	  ret.add(r);
      }
    }
    return ret;
  }

  public void addRegion(Region region) {
    regions.add(region);
    emit(new Event(Event.Type.REGION_ADDED, region));
  }

  public void removeRegion(Region region) {
    regions.remove(region);
    emit(new Event(Event.Type.REGION_REMOVED, region));
  }

  /** Return an equivalent Tabula Page object. Note that Tabula "Page"s can be
   * either the whole page or an area of the page. This gets the former.
   */
  public technology.tabula.Page toTabulaPage() {
    PDDocument pdDoc = document.getPDDocument();
    technology.tabula.Page tabulaPage =
      new ObjectExtractor(pdDoc).extract(pageIndex + 1);
    tabulaPage =
      VisibilityFilter.removeInvisibleTextElements(pdDoc, pageIndex, tabulaPage);
    return tabulaPage;
  }

  /** Return a list of regions detected as possible tables on this page. If
   * within!=null, detect tables only within that region.
   */
  public List<Region> detectTableRegions(Region within) throws IOException {
    if (within == null && detectedTableRegions) { // already did it
      // just get the regions whose source is TABLE
      // FIXME? this will also get tables that were detected previously with
      // within!=null... do we care? is that actually the right thing?
      List<Region> ret = new LinkedList<Region>();
      synchronized (regions) {
	for (Region r : regions)
	  if (r.source == Region.Source.TABLE)
	    ret.add(r);
      }
      return ret;
    }
    // make a duplicate document containing a duplicate of this page, since
    // Tabula steals the page into a document of its own when you call detect()
    // (ARGH)
    PDDocument docCopy = new PDDocument();
    PDPage pdPageCopy = docCopy.importPage(pdPage);
    pdPageCopy.setRotation(pdPage.getRotation());
    technology.tabula.Page tabulaPage = new ObjectExtractor(docCopy).extract(1);
    tabulaPage =
      VisibilityFilter.removeInvisibleTextElements(docCopy, 0, tabulaPage);
    // instead of just this:
    //technology.tabula.Page tabulaPage = toTabulaPage();
    // if within was given, get the tabula "Page" that is just the part of the
    // page within that rectangle
    if (within != null)
      tabulaPage = tabulaPage.getArea(within.rect);
    // now we can call detect()
    List<Rectangle> rects = new NurminenDetectionAlgorithm().detect(tabulaPage);
    // and wrap the results in our Region objects
    List<Region> regs = new ArrayList<Region>(rects.size());
    for (Rectangle rect : rects) {
      // Tabula sometimes gives out-of-bounds rects (!)
      Rectangle clampedRect = Region.clampRectToPage(rect, this);
      regs.add(new Region(clampedRect, this, Region.Source.TABLE));
    }
    docCopy.close(); // close the duplicate document so PDFBox doesn't complain
    if (within == null)
      detectedTableRegions = true;
    return regs;
  }

  public List<Region> detectTableRegions() throws IOException {
    return detectTableRegions(null);
  }

  /** Could str be used to mark a list item (like a bullet point)? */
  public static boolean isListItemMarker(String str) {
    // 1-3 non-word chars, 1 word char with paren(s) or a dot, or small roman
    // numerals maybe with a dot; all with optional whitespace around
    return str.matches("\\s*(\\W{1,3}|\\(?\\w\\)|\\w\\.?|[ivx]{1,6}\\.?)\\s*");
  }

  /** Kind of like an incremental MergedCell. Used within
   * detectParagraphRegions.
   */
  class Paragraph {
    // (we use RTC here instead of TextChunk because MergedCell.fromRTCs
    // expects a List<RTC>)
    LinkedList<RectangularTextContainer> lines;
    MergedCell cell;

    /** Make a paragraph with first as its only line. */
    public Paragraph(RectangularTextContainer first) {
      lines = new LinkedList<RectangularTextContainer>();
      add(first, false);
    }

    void updateCell() {
      cell = MergedCell.fromRTCs(lines, false, true);
    }

    /** Add next as a new line of the paragraph. */
    void addVertically(RectangularTextContainer next) {
      lines.add(next);
    }

    /** Add next to the end of the last line of the paragraph. */
    void addHorizontally(RectangularTextContainer next) {
      RectangularTextContainer prev = lines.removeLast();
      List<RectangularTextContainer> prevAndNext =
	new ArrayList<RectangularTextContainer>(2);
      prevAndNext.add(prev);
      prevAndNext.add(next);
      lines.add(MergedCell.fromRTCs(prevAndNext, true, true));
    }

    /** Add next either vertically or horizontally, and update cell. */
    public void add(RectangularTextContainer next, boolean horizontally) {
      if (horizontally) {
	addHorizontally(next);
      } else {
	addVertically(next);
      }
      updateCell();
    }

    /** Get the number of lines in this paragraph. */
    public int getLineCount() {
      return lines.size();
    }

    /** Get the width of a space in the font used by the last TextChunk. */
    public int getWidthOfSpace() {
      RectangularTextContainer line = getLastLine();
      TextChunk tc;
      if (line instanceof MergedCell) {
	List<HasText> te = ((MergedCell)line).textElements; // ick.
	HasText ht = te.get(te.size()-1); // double ick. (no List#getLast())
	tc = (TextChunk)ht;
      } else { // assume TextChunk
        tc = (TextChunk)line;
      }
      return (int)tc.getTextElements().get(0).getWidthOfSpace();
    }

    /** Get the last line without removing it. */
    public RectangularTextContainer getLastLine() {
      return lines.getLast();
    }

    /** Remove the last line and return it, after updating cell. */
    public RectangularTextContainer removeLastLine() {
      RectangularTextContainer last = lines.removeLast();
      updateCell();
      return last;
    }

    /** Finish this paragraph and turn it into a Region. */
    public Region finish() {
      return new Region(cell, Page.this, Region.Source.PARAGRAPH);
    }
  }

  /** Sometimes Tabula's TextElement.mergeWords() erroneously makes TextChunks
   * with large horizontal gaps; this splits them. Often this is caused by a
   * superscript that extends slightly above the rest of the line, which gets
   * attached to a line from another column, so we detect that here too and try
   * to attach it in a more reasonable place.
   */
  static List<TextChunk> splitBogusTextChunks(List<TextChunk> oldTextChunks) {
    List<TextChunk> newTextChunks =
      new ArrayList<TextChunk>(oldTextChunks.size());
    List<TextElement> superscripts = new LinkedList<TextElement>();
    for (TextChunk c : oldTextChunks) { // contiguous lines of text
      // find out whether each column of pixels is covered by a character's bbox
      double minX = c.getMinX();
      int width = (int)Math.ceil(c.getWidth()) + 1;
      boolean[] covered = new boolean[width];
      int minGapWidth = 0; // also find max space width and use it for min gap w
      for (TextElement e : c.getTextElements()) { // characters
	int start = (int)Math.floor(e.getMinX() - minX);
	if (start < 0) {
	  System.err.println("warning: " + e.toString() + " seems to extend to the left of its container " + c.toString());
	  start = 0;
	}
	int end = (int)Math.ceil(e.getMaxX() - minX);
	if (end >= width) {
	  System.err.println("warning: " + e.toString() + " seems to extend to the right of its container " + c.toString());
	  end = width - 1;
	}
	for (int i = start; i <= end; i++) covered[i] = true;
	int wos = (int)Math.round(e.getWidthOfSpace());
	if (wos > minGapWidth) minGapWidth = wos;
      }
      if (minGapWidth == 0) {
	//throw new RuntimeException("WTF");
	System.err.println("WARNING: chunk has no elements with wos>=0.5; setting minGapWidth=1");
	System.err.println(c.toString());
	minGapWidth = 1;
      }
      // look for gaps in that coverage
      List<Double> gapStarts = new LinkedList<Double>();
      int lastCovered = -1;
      int i = 0;
      for (; i < width; i++) {
	if (covered[i]) {
	  if (i - lastCovered > minGapWidth)
	    gapStarts.add(minX + lastCovered + 1);
	  lastCovered = i;
	}
      }
      if (i - lastCovered > minGapWidth)
	gapStarts.add(minX + lastCovered + 1);
      // add new chunk(s)
      if (gapStarts.size() == 0) { // no gaps, just add the old chunk
	newTextChunks.add(c);
      } else { // have gaps, make new chunks and add them
	TextChunk[] ntcs = new TextChunk[gapStarts.size()+1];
	for (TextElement e : c.getTextElements()) {
	  int j = 0;
	  for (Double start : gapStarts) {
	    if (start > e.getMinX()) break;
	    j++;
	  }
	  if (ntcs[j] == null) {
	    ntcs[j] = new TextChunk(e);
	  } else {
	    ntcs[j].add(e);
	  }
	}
	for (int j = 0; j < ntcs.length; j++) {
	  List<TextElement> tes = ntcs[j].getTextElements();
	  if (tes.size() == 1) { // 1 char, might be a superscript
	    superscripts.add(tes.get(0));
	  } else {
	    newTextChunks.add(ntcs[j]);
	  }
	}
      }
    }
    // try to add superscripts in more appropriate places
    for (TextElement ss : superscripts) {
      boolean added = false;
      for (TextChunk c : newTextChunks) {
	if (ss.intersects(c)) {
	  c.add(ss);
	  added = true;
	  break;
	}
      }
      if (!added) // didn't find a place to add it, just add its own TextChunk
	newTextChunks.add(new TextChunk(ss));
    }
    return newTextChunks;
  }

  /** Order TextChunks representing lines of paragraphs in reading order by
   * their minimum coordinates, and failing that, by their maximum coordinates.
   * Note that Tabula pretty much already gives us TextChunks in reading order,
   * but splitBogusTextChunks above can mess that up slightly.
   */
  static class ParagraphLineComparator implements Comparator<TextChunk> {
    @Override
    public int compare(TextChunk a, TextChunk b) {
      int cMinY = Double.compare(a.getMinY(), b.getMinY());
      if (cMinY != 0) return cMinY;
      int cMinX = Double.compare(a.getMinX(), b.getMinX());
      if (cMinX != 0) return cMinX;
      int cMaxY = Double.compare(a.getMaxY(), b.getMaxY());
      if (cMaxY != 0) return cMaxY;
      int cMaxX = Double.compare(a.getMaxX(), b.getMaxX());
      return cMaxX;
    }
  }

  final static boolean debugDPR = false;
  /** Construct regions on this page, each containing a contiguous paragraph. */
  public List<Region> detectParagraphRegions() throws IOException {
    if (detectedParagraphRegions) { // already did it
      // just get the regions whose source is PARAGRAPH
      List<Region> ret = new LinkedList<Region>();
      synchronized (regions) {
	for (Region r : regions)
	  if (r.source == Region.Source.PARAGRAPH)
	    ret.add(r);
      }
      return ret;
    }
    // copy PDPage into its own document (see above)
    PDDocument docCopy = new PDDocument();
    PDPage pdPageCopy = docCopy.importPage(pdPage);
    pdPageCopy.setRotation(pdPage.getRotation());
    // get a tabula Page from that
    technology.tabula.Page tabulaPage = new ObjectExtractor(docCopy).extract(1);
    tabulaPage =
      VisibilityFilter.removeInvisibleTextElements(docCopy, 0, tabulaPage);
    // get the individual-character text elements from that
    List<TextElement> textElements = tabulaPage.getText();
    List<TextChunk> textChunks;
    if (USE_TEXT_CHUNKER) {
      // merge those into contiguous lines
      textChunks = TextChunker.chunk(textElements);
    } else {
      // merge those into contiguous lines (not words as the method name
      // suggests)
      textChunks = TextElement.mergeWords(textElements);
      // ...but not into lines spanning the whole width of the page
      //List<Line> lines = TextChunk.groupByLines(textChunks);
      // mergeWords sometimes makes lines with big gaps; split them
      textChunks = splitBogusTextChunks(textChunks);
      // the above can upset the order of chunks, so sort them again
      textChunks.sort(new ParagraphLineComparator());
    }
    
    // map from x coordinate to the lines of the paragraph left-aligned at that
    // x coordinate, which we have not yet finished collecting
    Map<Integer,Paragraph> currentParagraphs =
      new ConcurrentSkipListMap<Integer,Paragraph>();
    // rectangular regions bounding the finished paragraphs' lines
    List<Region> finishedRegions = new LinkedList<Region>();
    // scan textChunks, adding each to a paragraph line list
    for (TextChunk c : textChunks) {
      int cLeft = (int)c.getLeft();
      int cTop = (int)c.getTop();
      int cHeight = (int)c.getHeight();
      int cSpaceWidth = (int)c.getTextElements().get(0).getWidthOfSpace();
      Paragraph paragraph = null;
      boolean cIsLIM = isListItemMarker(c.getText());
      if (debugDPR) System.err.println("chunk l="+cLeft+";t="+cTop+";h="+cHeight+";isLIM="+cIsLIM+";sw=" + cSpaceWidth + ";text=" + c.getText());
      boolean addHorizontally = false;
      int fixEntryAtKey = -1;
      // left x coordinate (key) of each paragraph we just finished
      List<Integer> finishedKeys = new LinkedList<Integer>();
      // find the existing paragraph to add c to, if any, and finish any
      // finishable paragraphs: those whose bottom is far enough from c's top
      // that it looks like a paragraph break, and those that horizontally
      // overlap c without being compatible with c as their next line
      for (Map.Entry<Integer,Paragraph> entry : currentParagraphs.entrySet()) {
	int paragraphLeft = entry.getKey();
	Paragraph prevParagraph = entry.getValue();
	RectangularTextContainer prevLine = prevParagraph.getLastLine();
	int pLeft = paragraphLeft; //(int)prevLine.getLeft(); don't use bullet
	int pRight = (int)prevLine.getRight();
	int pBottom = (int)prevLine.getBottom();
	int pHeight = (int)prevLine.getHeight();
	int pSpaceWidth = prevParagraph.getWidthOfSpace();
	int twiceAvgHeight = (int)((cHeight + pHeight) * (2.5 / 2.0)); // I lied
	int vSep = cTop - pBottom;
	if (debugDPR) System.err.println(" paragraph l="+pLeft+";r="+pRight+";b="+pBottom+";h="+pHeight+";sw="+pSpaceWidth+";2ah="+twiceAvgHeight+";vSep="+vSep);
	if (vSep >= twiceAvgHeight) { // paragraph-breaking whitespace
	  if (debugDPR) System.err.println("  finished by vSep");
	  finishedKeys.add(paragraphLeft);
	} else if (prevParagraph.getLineCount() == 1 &&
	           isListItemMarker(prevLine.getText()) &&
		   prevLine.verticallyOverlaps(c) &&
		   pRight < cLeft && cLeft - pRight <= 8 * pSpaceWidth
		  ) { // prevLine is a bullet point
	  if (debugDPR) System.err.println("  extended by adding to last line");
	  paragraph = prevParagraph;
	  addHorizontally = true;
	  fixEntryAtKey = paragraphLeft;
	} else if (prevParagraph.cell.horizontallyOverlaps(c)) {
	  if ((!cIsLIM) && // c is not a bullet point
	      paragraph == null && // we didn't already add c to a bullet point
	      cSpaceWidth == pSpaceWidth && // same font size
	      (cLeft == pLeft || // aligned
	       (cLeft < pLeft && pLeft - cLeft <= 8 * pSpaceWidth &&
	        prevParagraph.getLineCount() == 1) // indented first line
	      )) {
	    if (debugDPR) System.err.println("  extended by adding a line");
	    paragraph = prevParagraph;
	    if (cLeft != paragraphLeft)
	      fixEntryAtKey = paragraphLeft;
	  } else { // overlaps but incompatible, break paragraph
	    if (debugDPR) System.err.println("  finished by incompatible overlap/bullet");
	    finishedKeys.add(paragraphLeft);
	  }
	}
      }
      // actually finish paragraphs, removing them from currentParagraphs,
      // converting them to Regions, and adding them to finishedRegions
      for (int paragraphLeft : finishedKeys) {
	Paragraph finishedParagraph = currentParagraphs.remove(paragraphLeft);
	if (finishedParagraph.getLineCount() > 1 &&
	    finishedParagraph.cell.intersects(c)) {
	  // remove the last line of this paragraph so that the whole paragraph
	  // doesn't intersect the new one we would have added for c, and add
	  // that last line as a new paragraph
	  // this can happen with the first bullet point in a list, for example
	  RectangularTextContainer lastLine =
	    finishedParagraph.removeLastLine();
	  Paragraph newParagraph = new Paragraph(lastLine);
	  currentParagraphs.put((int)lastLine.getLeft(), newParagraph);
	}
	Region r = finishedParagraph.finish();
	if (debugDPR) {
	  System.err.println("finished region:");
	  System.err.println(r.toKQML().toString());
	  System.err.println(finishedParagraph.cell.getText());
	}
	finishedRegions.add(r);
      }
      if (paragraph == null) { // didn't find a compatible paragraph, make one
	if (debugDPR) System.err.println(" started new paragraph");
	paragraph = new Paragraph(c);
	currentParagraphs.put(cLeft, paragraph);
      } else {
	paragraph.add(c, addHorizontally);
	if (fixEntryAtKey >= 0) // adding c changes the key
	  currentParagraphs.put(cLeft, currentParagraphs.remove(fixEntryAtKey));
      }
    }
    // finish any remaining paragraphs
    for (Map.Entry<Integer,Paragraph> entry : currentParagraphs.entrySet()) {
      Paragraph finishedParagraph = entry.getValue();
      finishedRegions.add(finishedParagraph.finish());
    }
    // TODO? order finishedRegions in document reading order somehow (find columns)
    docCopy.close(); // close the duplicate document so PDFBox doesn't complain
    detectedParagraphRegions = true;
    return finishedRegions;
  }
  
  public List<Region> detectRulingRegions() throws IOException {
    if (detectedRulingRegions) { // already did it
      // just get the regions whose source is HORIZONTAL or VERTICAL
      List<Region> ret = new LinkedList<Region>();
      synchronized (regions) {
	for (Region r : regions)
	  if (r.source == Region.Source.HORIZONTAL ||
	      r.source == Region.Source.VERTICAL)
	    ret.add(r);
      }
      return ret;
    }
    List<Region> regs = new LinkedList<Region>();
    for (Ruling ruling : toTabulaPage().getRulings()) {
      float pos = ruling.getPosition();
      float start = ruling.getStart();
      float end = ruling.getEnd();
      // make sure start and end are in order
      if (start > end) {
	float tmp = start;
	start = end;
	end = tmp;
      }
      if (end - start <= 1) continue; // skip 1px "rulings"
      if (ruling.horizontal()) {
	regs.add(new Region(start, pos, end, pos,
			    this, Region.Source.HORIZONTAL));
      } else if (ruling.vertical()) {
	regs.add(new Region(pos, start, pos, end,
			    this, Region.Source.VERTICAL));
      } // else oblique, ignore these
    }
    detectedRulingRegions = true;
    return regs;
  }

  @Override public KQMLObject toKQML() { return toKQML(true); }
  public KQMLObject toKQML(boolean detailed) {
    KQMLPerformative p = new KQMLPerformative("page");
    p.setParameter(":id", id);
    p.setParameter(":document", document.getID());
    p.setParameter(":index", Integer.toString(pageIndex));
    if (detailed) {
      KQMLPerformative bounds = new KQMLPerformative("rectangle");
      PDRectangle pdbb = getPDBBox();
      bounds.setParameter(":x", Float.toString(pdbb.getLowerLeftX()));
      bounds.setParameter(":y", Float.toString(pdbb.getLowerLeftY()));
      bounds.setParameter(":w", Float.toString(pdbb.getWidth()));
      bounds.setParameter(":h", Float.toString(pdbb.getHeight()));
      p.setParameter(":bounds", bounds);
    }
    return p;
  }

  public static Page fromKQML(KQMLObject listOrID) throws CWCException, KQMLBadPerformativeException {
    if (listOrID instanceof KQMLList) {
      return fromKQML(new KQMLPerformative((KQMLList)listOrID));
    } else if (listOrID instanceof KQMLPerformative) {
      KQMLPerformative perf = (KQMLPerformative)listOrID;
      KQMLToken idKQML =
	Args.getTypedArgument(perf, ":id", KQMLToken.class, new KQMLToken("nil"));
      String id = idKQML.toString().toLowerCase();
      if (id.equals("nil")) { // have no ID, get the page from the document
	KQMLObject docKQML =
	  Args.getTypedArgument(perf, ":document", KQMLObject.class);
	Document doc = Document.fromKQML(docKQML);
	int index = Args.getTypedArgument(perf, ":index", Integer.class);
	return doc.getPage(index);
      } else { // have ID, just get the existing object
        return HasID.get(id, Page.class);
      }
    } else if (listOrID instanceof KQMLToken) {
      return HasID.get(listOrID.toString(), Page.class);
    } else {
      throw new InvalidArgument("nil", ":page", "a list or id", listOrID);
    }
  }

  @Override
  public List<TextMatch> search(Pattern searchPattern) {
    List<TextMatch> matches = new ArrayList<TextMatch>();
    synchronized (regions) {
      for (Region r : regions)
	matches.addAll(r.search(searchPattern));
    }
    return matches;
  }

  /** A user interaction with a {@link Page}, involving a {@link Region} on it.
   */
  public static class Event {
    public static enum Type { REGION_ADDED, REGION_CHANGED, REGION_STOPPED_CHANGING, REGION_REMOVED, REGION_HIGHLIGHTED, REGION_UNHIGHLIGHTED };
    Type type;		public Type getType() { return type; }
    Region region;	public Region getRegion() { return region; }
    public Page getPage() { return region.getPage(); }
    public Event(Type type, Region region) {
      this.type = type;
      this.region = region;
    }
  }

  /** Listener for {@link Event}s emitted by a {@link Page}. */
  public interface Listener {
    void pageChanged(Event pageEvent);
  }
  
  public void addPageListener(Listener listener) {
    listeners.add(listener);
  }

  public void emit(Event event) {
    for (Listener l : listeners) {
      l.pageChanged(event);
    }
  }
}
